package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeaturePersistenceTest {
    @TempDir Path directory;
    static final String GRAVE = UUID.randomUUID() + "/100/" + UUID.randomUUID() + "/1/2/3";
    static final UUID HELPER = UUID.randomUUID();
    static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(3, TimeUnit.SECONDS); }
    static final class Disk implements DurableFeatureState.Disk {
        Map<String,String> values = Map.of();
        volatile boolean fail;
        volatile CountDownLatch entered, release;
        String thread;
        public Map<String,String> load() { return values; }
        public void save(Map<String,String> next) throws Exception {
            thread = Thread.currentThread().getName();
            if (entered != null) { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); }
            if (fail) throw new IOException("injected save failure");
            values = Map.copyOf(next);
        }
    }
    @Test void grantOnlyPublishesAfterWorkerAcknowledges() throws Exception {
        try (FeatureIO io = new FeatureIO()) {
            Disk disk = new Disk(); DurableFeatureState state = new DurableFeatureState(io, disk, 4096);
            TrustedGraveAccessStore store = new TrustedGraveAccessStore(state, () -> 100L); assertTrue(await(store.loaded()));
            disk.entered = new CountDownLatch(1); disk.release = new CountDownLatch(1);
            var write = store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK, 200);
            assertTrue(disk.entered.await(2, TimeUnit.SECONDS));
            assertFalse(write.isDone()); assertFalse(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK));
            disk.release.countDown(); assertTrue(await(write));
            assertTrue(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK));
            assertEquals("gsp-feature-io", disk.thread);
        }
    }
    @Test void failedGrantNeverAuthorizesAndRestartReconcilesOriginal() throws Exception {
        Disk disk = new Disk();
        try (FeatureIO io = new FeatureIO()) {
            DurableFeatureState state = new DurableFeatureState(io, disk, 4096);
            var store = new TrustedGraveAccessStore(state, () -> 100L); await(store.loaded()); disk.fail = true;
            assertFalse(await(store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER, 200)));
            assertFalse(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER));
        }
        disk.fail = false;
        try (FeatureIO io = new FeatureIO()) {
            var store = new TrustedGraveAccessStore(new DurableFeatureState(io, disk, 4096), () -> 100L);
            await(store.loaded()); assertFalse(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER));
        }
    }
    @Test void revocationDeniesBeforeAcknowledgmentAndSurvivesRestart() throws Exception {
        Disk disk = new Disk();
        try (FeatureIO io = new FeatureIO()) {
            var store = new TrustedGraveAccessStore(new DurableFeatureState(io, disk, 4096), () -> 100L); await(store.loaded());
            await(store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER, 200));
            disk.entered = new CountDownLatch(1); disk.release = new CountDownLatch(1);
            var revoke = store.revokeAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER);
            assertFalse(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER));
            assertTrue(disk.entered.await(2, TimeUnit.SECONDS)); disk.release.countDown(); assertTrue(await(revoke));
        }
        try (FeatureIO io = new FeatureIO()) {
            var store = new TrustedGraveAccessStore(new DurableFeatureState(io, disk, 4096), () -> 100L);
            await(store.loaded()); assertFalse(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER));
        }
    }
    @Test void failedRevocationDoesNotClaimDurabilityOrRestoreLocalAccess() throws Exception {
        try (FeatureIO io = new FeatureIO()) {
            Disk disk = new Disk(); var store = new TrustedGraveAccessStore(new DurableFeatureState(io, disk, 4096), () -> 100L);
            await(store.loaded()); await(store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER, 200)); disk.fail = true;
            assertFalse(await(store.revokeAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER)));
            assertFalse(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER));
            assertFalse(disk.values.isEmpty()); // restart still needs reconciliation if revocation did not save
        }
    }
    @Test void kindsIdentitiesExpiryAndLifetimesAreEnforcedByStore() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        try (FeatureIO io = new FeatureIO()) {
            var store = new TrustedGraveAccessStore(new DurableFeatureState(io, new Disk(), 4096), clock::get); await(store.loaded());
            assertFalse(await(store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK, 0)));
            assertFalse(await(store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK, 101 + TrustedGraveAccessStore.MAX_LIFETIME)));
            assertTrue(await(store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK, 200)));
            assertFalse(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER));
            assertFalse(store.allows(GRAVE + "0", HELPER, TrustedGraveAccess.Kind.TRACK));
            assertFalse(store.allows(GRAVE, UUID.randomUUID(), TrustedGraveAccess.Kind.TRACK));
            clock.set(200); assertFalse(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK));
        }
    }

    @Test void offlineUuidGrantRoundTripsAndKeepsKindAndGraveBoundaries() throws Exception {
        Disk disk = new Disk();
        UUID offlineHelper = UUID.randomUUID();
        String otherGrave = GRAVE + "/other";
        try (FeatureIO io = new FeatureIO()) {
            var store = new TrustedGraveAccessStore(new DurableFeatureState(io, disk, 4096), () -> 100L);
            assertTrue(await(store.loaded()));
            assertTrue(await(store.grantAsync(GRAVE, offlineHelper, TrustedGraveAccess.Kind.RECOVER, 200)));
            assertTrue(store.allows(GRAVE, offlineHelper, TrustedGraveAccess.Kind.RECOVER));
            assertFalse(store.allows(GRAVE, offlineHelper, TrustedGraveAccess.Kind.TRACK));
            assertFalse(store.allows(otherGrave, offlineHelper, TrustedGraveAccess.Kind.RECOVER));
        }
        try (FeatureIO io = new FeatureIO()) {
            var reopened = new TrustedGraveAccessStore(new DurableFeatureState(io, disk, 4096), () -> 100L);
            assertTrue(await(reopened.loaded()));
            assertTrue(reopened.allows(GRAVE, offlineHelper, TrustedGraveAccess.Kind.RECOVER));
            assertFalse(reopened.allows(GRAVE, offlineHelper, TrustedGraveAccess.Kind.TRACK));
            assertFalse(reopened.allows(otherGrave, offlineHelper, TrustedGraveAccess.Kind.RECOVER));
        }
    }
    @Test void capacityAllowsReplacementAndRevocationAndPrunesExpired() throws Exception {
        AtomicLong clock = new AtomicLong(100);
        try (FeatureIO io = new FeatureIO()) {
            var store = new TrustedGraveAccessStore(new DurableFeatureState(io, new Disk(), 1), clock::get); await(store.loaded());
            assertTrue(await(store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK, 200)));
            assertTrue(await(store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK, 201)));
            assertFalse(await(store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER, 201)));
            assertTrue(await(store.revokeAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK)));
            assertTrue(await(store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER, 201)));
            clock.set(202); assertTrue(await(store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK, 300)));
        }
    }
    @Test void boundedQueueRejectionAndShutdownCompleteExceptionally() throws Exception {
        FeatureIO io = new FeatureIO(1);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var first = io.submit(() -> { entered.countDown(); try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); } return true; });
        assertTrue(entered.await(2, TimeUnit.SECONDS)); var queued = io.submit(() -> true);
        assertThrows(ExecutionException.class, () -> await(io.submit(() -> true)));
        release.countDown(); assertTrue(await(first)); assertTrue(await(queued)); io.close();
        assertThrows(ExecutionException.class, () -> await(io.submit(() -> true)));
    }
    @Test void realAtomicFileRoundTripAndMalformedStateFailClosed() throws Exception {
        Path file = directory.resolve("state.yml");
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, file, 4); assertTrue(await(state.loaded()));
            assertTrue(await(state.update(next -> { next.put(GRAVE, "value"); return true; })));
        }
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, file, 4); assertTrue(await(state.loaded())); assertEquals("value", state.snapshot().get(GRAVE));
        }
        Files.writeString(file, "entries: [broken");
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, file, 4); assertFalse(await(state.loaded()));
            assertFalse(await(state.update(next -> { next.clear(); return true; })));
        }
    }
    @Test void concurrentOwnerHelperReserveHasOnlyOneWinnerAndRestartBlocksReplay() throws Exception {
        Disk disk = new Disk();
        try (FeatureIO io = new FeatureIO()) {
            var ledger = new GraveClaimLedger(new DurableFeatureState(io, disk, 8192)); await(ledger.loaded());
            var owner = ledger.reserve(GRAVE, UUID.randomUUID()); var helper = ledger.reserve(GRAVE, HELPER);
            assertTrue(await(owner)); assertFalse(await(helper)); assertTrue(ledger.isBlocked(GRAVE));
        }
        try (FeatureIO io = new FeatureIO()) {
            var ledger = new GraveClaimLedger(new DurableFeatureState(io, disk, 8192)); await(ledger.loaded());
            assertTrue(ledger.isBlocked(GRAVE)); assertTrue(ledger.status(GRAVE).startsWith("RESERVED:"));
            assertFalse(await(ledger.reserve(GRAVE, HELPER)));
        }
    }
    @Test void cancelledClaimOnlyUnlocksAfterAcknowledgmentAndPaidClaimNeverReplays() throws Exception {
        try (FeatureIO io = new FeatureIO()) {
            Disk disk = new Disk(); var ledger = new GraveClaimLedger(new DurableFeatureState(io, disk, 8192)); await(ledger.loaded());
            assertTrue(await(ledger.reserve(GRAVE, HELPER)));
            disk.entered = new CountDownLatch(1); disk.release = new CountDownLatch(1);
            var cancel = ledger.cancel(GRAVE); assertTrue(disk.entered.await(2, TimeUnit.SECONDS)); assertTrue(ledger.isBlocked(GRAVE));
            disk.release.countDown(); assertTrue(await(cancel)); assertFalse(ledger.isBlocked(GRAVE));
            assertTrue(await(ledger.reserve(GRAVE, HELPER))); assertTrue(await(ledger.paid(GRAVE, HELPER)));
            assertTrue(ledger.isBlocked(GRAVE)); assertEquals("PAID:" + HELPER, ledger.status(GRAVE));
        }
    }
    @Test void failedClaimReservationHasNoAcknowledgmentAndStaysBlocked() throws Exception {
        try (FeatureIO io = new FeatureIO()) {
            Disk disk = new Disk(); var ledger = new GraveClaimLedger(new DurableFeatureState(io, disk, 8192)); await(ledger.loaded()); disk.fail = true;
            assertFalse(await(ledger.reserve(GRAVE, HELPER))); assertTrue(ledger.isBlocked(GRAVE)); assertTrue(disk.values.isEmpty());
        }
    }
    @Test void emptyPublishedStateAndLastRevocationRemainReadableAfterRestart() throws Exception {
        Path file = directory.resolve("empty.yml");
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, file, 4); await(state.loaded());
            assertTrue(await(state.update(next -> { next.put("one", "value"); return true; })));
            assertTrue(await(state.update(next -> { next.clear(); return true; })));
        }
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, file, 4); assertTrue(await(state.loaded())); assertTrue(state.isReady()); assertTrue(state.snapshot().isEmpty());
        }
        // Also reconcile files produced by the initial versioned empty writer.
        Files.writeString(file, "version: 1\n");
        try (FeatureIO io = new FeatureIO()) { assertTrue(await(new DurableFeatureState(io, file, 4).loaded())); }
    }
    @Test void olderGrantAcknowledgmentCannotOverrideNewerPendingRevocation() throws Exception {
        var firstEntered = new CountDownLatch(1); var firstRelease = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1); var secondRelease = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        DurableFeatureState.Disk disk = new DurableFeatureState.Disk() {
            public Map<String,String> load() { return Map.of(); }
            public void save(Map<String,String> state) throws Exception {
                if (writes.incrementAndGet() == 1) { firstEntered.countDown(); assertTrue(firstRelease.await(3, TimeUnit.SECONDS)); }
                else { secondEntered.countDown(); assertTrue(secondRelease.await(3, TimeUnit.SECONDS)); }
            }
        };
        try (FeatureIO io = new FeatureIO()) {
            var store = new TrustedGraveAccessStore(new DurableFeatureState(io, disk, 4096), () -> 100L); await(store.loaded());
            var grant = store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER, 200);
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS)); var revoke = store.revokeAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER);
            firstRelease.countDown(); assertTrue(await(grant)); assertTrue(secondEntered.await(2, TimeUnit.SECONDS));
            assertFalse(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER));
            secondRelease.countDown(); assertTrue(await(revoke)); assertFalse(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER));
        } finally { firstRelease.countDown(); secondRelease.countDown(); }
    }
    @Test void shutdownWaitsForAcceptedWritesPastOldTwoSecondReturn() throws Exception {
        FeatureIO io = new FeatureIO(1); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        AtomicInteger published = new AtomicInteger();
        var busy = io.submit(() -> { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); } return true; });
        assertTrue(entered.await(2, TimeUnit.SECONDS)); var finalWrite = io.submit(published::incrementAndGet);
        var closing = CompletableFuture.runAsync(io::close);
        try { Thread.sleep(2200); assertFalse(closing.isDone()); assertEquals(0, published.get()); }
        finally { release.countDown(); }
        await(busy); await(finalWrite); await(closing); assertTrue(io.isTerminated()); assertEquals(1, published.get());
    }

    @Test void admissionGuaranteesQueueSpaceWhileCompletedWorkerIsStillDraining() throws Exception {
        try (FeatureIO io = new FeatureIO(1)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            io.onDrain(() -> { entered.countDown(); try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); } });
            var first = io.submit(() -> true); assertTrue(entered.await(2, TimeUnit.SECONDS));
            var one = io.admit(); var two = io.admit(); assertNotNull(one); assertNotNull(two); assertNull(io.admit());
            var savedOne = one.submit(() -> true); var savedTwo = two.submit(() -> true);
            assertFalse(savedTwo.isCompletedExceptionally()); release.countDown();
            assertTrue(await(first)); assertTrue(await(savedOne)); assertTrue(await(savedTwo));
        }
    }

    @Test void grantAndRevocationAdmissionFollowTheirOperationOrderAcrossThreads() throws Exception {
        DurableFeatureState state = org.mockito.Mockito.mock(DurableFeatureState.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var revoking = new CountDownLatch(1);
        var values = new java.util.concurrent.ConcurrentHashMap<String,String>(); AtomicInteger calls = new AtomicInteger();
        org.mockito.Mockito.when(state.update(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); }
            java.util.function.Function<Map<String,String>,Boolean> mutation = call.getArgument(0);
            mutation.apply(values); return CompletableFuture.completedFuture(true);
        });
        org.mockito.Mockito.when(state.isReady()).thenReturn(true); org.mockito.Mockito.when(state.snapshot()).thenAnswer(call -> Map.copyOf(values));
        var store = new TrustedGraveAccessStore(state, () -> 100L);
        var grant = CompletableFuture.supplyAsync(() -> store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER, 200));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var revoke = CompletableFuture.supplyAsync(() -> { revoking.countDown(); return store.revokeAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER); });
        assertTrue(revoking.await(2, TimeUnit.SECONDS));
        try { assertThrows(TimeoutException.class, () -> revoke.get(100, TimeUnit.MILLISECONDS)); }
        finally { release.countDown(); }
        assertTrue(await(await(grant))); assertTrue(await(await(revoke))); assertTrue(values.isEmpty());
        assertFalse(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.RECOVER));
    }

    @Test void shutdownHonorsUnusedPublicationTicketInsteadOfRejectingItsSubmission() throws Exception {
        FeatureIO io = new FeatureIO(1); FeatureIO.Admission admitted = io.admit(); assertNotNull(admitted);
        var entered = new CountDownLatch(1);
        var closing = CompletableFuture.runAsync(() -> { entered.countDown(); io.close(); });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        // close cannot return or reject this ticket while the sender is using it.
        assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
        assertTrue(await(admitted.submit(() -> true))); await(closing); assertTrue(io.isTerminated());
    }
    @Test void graveCleanupDeniesPendingAndExistingGrantsUntilDurableRemoval() throws Exception {
        Disk disk = new Disk();
        try (FeatureIO io = new FeatureIO()) {
            var store = new TrustedGraveAccessStore(new DurableFeatureState(io, disk, 4096), () -> 100L); await(store.loaded());
            assertTrue(await(store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK, 200)));
            disk.entered = new CountDownLatch(1); disk.release = new CountDownLatch(1);
            var removal = store.revokeForGraveAsync(GRAVE);
            assertTrue(disk.entered.await(2, TimeUnit.SECONDS)); assertFalse(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK));
            disk.release.countDown(); assertTrue(await(removal)); assertFalse(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK));
            assertTrue(await(store.grantAsync(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK, 200))); assertTrue(store.allows(GRAVE, HELPER, TrustedGraveAccess.Kind.TRACK));
        }
    }

}
