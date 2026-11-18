package com.bencodez.gravestonesplus.tests.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import com.bencodez.gravestonesplus.graves.FeatureIO;
import com.bencodez.gravestonesplus.storage.*;

class SqlRecoveryJournalTest {
    @TempDir Path directory;
    static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    static GravesConfig prepared() throws Exception {
        var grave = new GravesConfig(UUID.randomUUID(), "Synthetic", new Location(null, 1, 64, 2),
                new HashMap<>(Map.of(0, new ItemStack(Material.STONE, 7))), 17, "synthetic", 1234, false, 0, null, null, "CHEST");
        grave.setWorldId(UUID.randomUUID());
        try (var encoding = mockStatic(InventorySerializer.class)) {
            encoding.when(() -> InventorySerializer.encode(any())).thenReturn("synthetic-encoded");
            return GraveStorageManager.snapshot(grave);
        }
    }
    SqlRecoveryJournal journal(FeatureIO io, GraveStorageBackend backend) throws Exception {
        var journal = new SqlRecoveryJournal(io, backend, directory, ignored -> {});
        assertTrue(await(journal.loaded())); await(io.barrier()); return journal;
    }
    @Test void rejectedStandaloneUpdateNeverAcquiresInsertAuthority() throws Exception {
        try (var io = new FeatureIO(4)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend);
            var record = prepared(); var ticket = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, true);
            assertNotNull(ticket); assertTrue(await(ticket.commit())); await(io.barrier());
            verify(backend).updateExistingGrave(any(GravesConfig.class), eq(true)); verify(backend, never()).upsertGrave(any(), anyBoolean());
            try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        }
    }
    @Test void failedInsertBlocksItsUpdateButBothDurableCommandsReplayInOrderAfterRestart() throws Exception {
        var record = prepared(); var calls = new CopyOnWriteArrayList<String>();
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend);
            doThrow(new IllegalStateException("synthetic outage")).when(backend).upsertGrave(any(), anyBoolean());
            var creation = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, record, false);
            var removal = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, true);
            assertNotNull(creation); assertNotNull(removal);
            creation.commit(); removal.commit(); await(io.barrier());
            verify(backend, never()).updateExistingGrave(any(), anyBoolean());
            try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
            journal.close();
        }
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class);
            doAnswer(call -> { calls.add("insert:" + call.getArgument(1)); return null; }).when(backend).upsertGrave(any(), anyBoolean());
            doAnswer(call -> { calls.add("update:" + call.getArgument(1)); return null; }).when(backend).updateExistingGrave(any(), anyBoolean());
            journal(io, backend); await(io.barrier());
            assertEquals(List.of("insert:false", "update:true"), calls);
            try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
        }
    }
    @Test void conditionalMissIsTerminalAndDoesNotPoisonLaterWrites() throws Exception {
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend); var record = prepared();
            doThrow(new SqlConditionNotMet()).when(backend).saveDisplayIdentity(any());
            var display = journal.reserve(SqlRecoveryJournal.Kind.DISPLAY, record, false);
            var flag = journal.reserve(SqlRecoveryJournal.Kind.FLAG, record, true);
            assertFalse(await(display.commit())); assertTrue(await(flag.commit()));
            verify(backend).saveDisplayIdentity(any(GravesConfig.class)); verify(backend).setBroken(any(GravesConfig.class), eq(true));
            verify(backend, never()).upsertGrave(any(), anyBoolean());
        }
    }
    @Test void reservationsHaveAnActualEntryCapAndReleaseWithoutWriting() throws Exception {
        try (var io = new FeatureIO(300)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend); var record = prepared();
            var held = new ArrayList<SqlRecoveryJournal.Reservation>();
            try {
                for (int i = 0; i < 256; i++) { var reservation = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, true); assertNotNull(reservation); held.add(reservation); }
                assertNull(journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, true));
            } finally { held.forEach(SqlRecoveryJournal.Reservation::close); }
            for (var cancelled : held) assertFalse(await(cancelled.commit()));
            await(io.barrier());
            verifyNoInteractions(backend);
        }
    }
    @Test void journalFailureBlocksNewAdmissionsAndDoesNotAcknowledgeSql() throws Exception {
        try (var io = new FeatureIO(4)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend); var record = prepared();
            var ticket = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, true); assertNotNull(ticket);
            Files.delete(directory); Files.writeString(directory, "synthetic unavailable directory");
            assertThrows(ExecutionException.class, () -> await(ticket.commit())); await(io.barrier());
            assertFalse(journal.isReady()); assertNull(journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, true)); verifyNoInteractions(backend);
        }
    }
    @Test void metadataOrderAndColumnIntentSurviveAnEarlierOutage() throws Exception {
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend); var record = prepared();
            var flagApplied = new CountDownLatch(1); var unavailable = new AtomicBoolean(true); var operations = new CopyOnWriteArrayList<String>();
            doAnswer(call -> { if (unavailable.get()) throw new IllegalStateException("synthetic outage"); operations.add("display"); return null; })
                    .when(backend).saveDisplayIdentity(any());
            doAnswer(call -> { operations.add("flag"); flagApplied.countDown(); return null; }).when(backend).setBroken(any(), eq(true));
            var display = journal.reserve(SqlRecoveryJournal.Kind.DISPLAY, record, false);
            var flag = journal.reserve(SqlRecoveryJournal.Kind.FLAG, record, true);
            display.commit(); flag.commit(); await(io.barrier()); assertTrue(operations.isEmpty());
            unavailable.set(false); journal.wake(); assertTrue(flagApplied.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("display", "flag"), operations);
            verify(backend, never()).upsertGrave(any(), anyBoolean()); verify(backend, never()).updateExistingGrave(any(), anyBoolean());
        }
    }
    @Test void heldPredecessorBlocksOnlyItsOwnIdentityAndReleaseWakesSuccessor() throws Exception {
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend); var record = prepared();
            var held = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, false);
            var later = journal.reserve(SqlRecoveryJournal.Kind.FLAG, record, true);
            try { later.commit(); await(io.barrier()); verifyNoInteractions(backend); }
            finally { held.close(); }
            await(io.barrier()); assertTrue(await(later.commit()));
            verify(backend).setBroken(any(GravesConfig.class), eq(true));
        }
    }
    @Test void corruptedRecoveryEntryBlocksStartupRatherThanReplayingChangedValues() throws Exception {
        try (var io = new FeatureIO(4)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend);
            doThrow(new IllegalStateException("synthetic outage")).when(backend).upsertGrave(any(), anyBoolean());
            journal.reserve(SqlRecoveryJournal.Kind.UPSERT, prepared(), false).commit(); await(io.barrier()); journal.close();
        }
        Path file; try (var files = Files.list(directory)) { file = files.findFirst().orElseThrow(); }
        byte[] corrupt = Files.readAllBytes(file); corrupt[corrupt.length - 1] ^= 1; Files.write(file, corrupt);
        try (var io = new FeatureIO(4)) {
            var backend = mock(GraveStorageBackend.class);
            var journal = new SqlRecoveryJournal(io, backend, directory, ignored -> {});
            assertFalse(await(journal.loaded())); assertFalse(journal.isReady()); verifyNoInteractions(backend);
        }
    }
    @Test void durablePrefixIsPublishedBeforeAnyBlockedJdbcCall() throws Exception {
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var occupied = io.submit(() -> { try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new AssertionError(e); } return true; });
            var first = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, prepared(), false);
            var second = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, prepared(), true);
            var jdbcRelease = new CountDownLatch(1);
            doAnswer(call -> { entered.countDown(); assertTrue(jdbcRelease.await(5, TimeUnit.SECONDS)); throw new IllegalStateException("synthetic outage"); })
                    .when(backend).upsertGrave(any(), anyBoolean());
            try {
                first.commit(); second.commit(); release.countDown(); assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(SqlRecoveryJournal.Disposition.DURABLE, await(first.durable())); assertEquals(SqlRecoveryJournal.Disposition.DURABLE, await(second.durable()));
                try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
            } finally { release.countDown(); jdbcRelease.countDown(); }
            await(occupied); await(io.barrier()); journal.close();
        }
    }
    @Test void reservedPredecessorFencesSuccessorDurabilityUntilItsCancellation() throws Exception {
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend); var record = prepared();
            var first = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, record, false);
            var later = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, true);
            try {
                later.commit(); await(io.barrier());
                assertFalse(later.durable().isDone()); try (var files = Files.list(directory)) { assertEquals(0, files.count()); }
            } finally { first.close(); }
            assertEquals(SqlRecoveryJournal.Disposition.DURABLE, await(later.durable())); assertTrue(await(later.commit()));
            verify(backend, never()).upsertGrave(any(), anyBoolean());
        }
    }
    @Test void firstExecutionUsesImmutableWireCapture() throws Exception {
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend); var record = prepared();
            var ticket = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, false);
            UUID identity = record.getDisplayUUID(); record.setDestroyed(true); record.setDisplayUUID(UUID.randomUUID());
            doAnswer(call -> {
                GravesConfig captured = call.getArgument(0); assertFalse(captured.isDestroyed()); assertEquals(identity, captured.getDisplayUUID());
                var encoded = GravesConfig.class.getDeclaredMethod("encodedItemsForStorage"); encoded.setAccessible(true);
                assertEquals("synthetic-encoded", encoded.invoke(captured)); return null;
            }).when(backend).updateExistingGrave(any(), anyBoolean());
            assertTrue(await(ticket.commit()));
        }
    }
    @Test void completeTemporaryFileIsRecoveredAndPartialFileIsPreservedWithoutBlockingStartup() throws Exception {
        try (var io = new FeatureIO(4)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend);
            doThrow(new IllegalStateException("synthetic outage")).when(backend).upsertGrave(any(), anyBoolean());
            var ticket = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, prepared(), false); ticket.commit(); await(ticket.durable()); await(io.barrier()); journal.close();
        }
        Path original; try (var files = Files.list(directory)) { original = files.findFirst().orElseThrow(); }
        Files.move(original, original.resolveSibling(original.getFileName() + ".tmp"));
        Path partial = directory.resolve("0000000000000000002.sql.tmp"); Files.write(partial, new byte[]{1,2,3});
        try (var io = new FeatureIO(4)) {
            var backend = mock(GraveStorageBackend.class); journal(io, backend); await(io.barrier());
            verify(backend).upsertGrave(any(), eq(false));
            assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(partial.resolveSibling(partial.getFileName() + ".incomplete")));
            try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
        }
    }
    @Test void renamedValidPayloadCannotChangeItsSequence() throws Exception {
        try (var io = new FeatureIO(4)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend);
            doThrow(new IllegalStateException("synthetic outage")).when(backend).upsertGrave(any(), anyBoolean());
            var ticket = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, prepared(), false); ticket.commit(); await(ticket.durable()); await(io.barrier()); journal.close();
        }
        Path original; try (var files = Files.list(directory)) { original = files.findFirst().orElseThrow(); }
        Files.move(original, directory.resolve("0000000000000000009.sql"));
        try (var io = new FeatureIO(4)) {
            var backend = mock(GraveStorageBackend.class); var journal = new SqlRecoveryJournal(io, backend, directory, ignored -> {});
            assertFalse(await(journal.loaded())); verifyNoInteractions(backend);
        }
    }
    @Test void externalThreadCannotExecuteSqlDrain() throws Exception {
        try (var io = new FeatureIO(4)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend);
            assertThrows(IllegalStateException.class, journal::drain); verifyNoInteractions(backend);
        }
    }
    @Test void byteBudgetRejectsBeforeEntryLimitAndOversizeEncodingCannotReserve() throws Exception {
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class);
            var constructor = SqlRecoveryJournal.class.getDeclaredConstructor(FeatureIO.class, GraveStorageBackend.class, Path.class, java.util.function.Consumer.class, int.class, long.class);
            constructor.setAccessible(true);
            var journal = constructor.newInstance(io, backend, directory, (java.util.function.Consumer<String>) ignored -> {}, 4, 256L);
            assertTrue(await(journal.loaded()));
            var first = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, prepared(), false); assertNotNull(first);
            SqlRecoveryJournal.Reservation overflow = null;
            try { overflow = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, prepared(), false); assertNull(overflow); }
            finally { if (overflow != null) overflow.close(); first.close(); }
            verifyNoInteractions(backend);
        }
    }
    @Test void blockedDurabilityObserverCannotBlockJdbcOrOwnItsThread() throws Exception {
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend);
            var release = new CountDownLatch(1); var observer = new CountDownLatch(1); var sql = new CountDownLatch(2);
            doAnswer(call -> { sql.countDown(); return null; }).when(backend).updateExistingGrave(any(), eq(false));
            var first = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, prepared(), false);
            first.durable().thenRun(() -> {
                assertFalse(io.isOwnerThread()); observer.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
            });
            try {
                first.commit(); assertTrue(observer.await(5, TimeUnit.SECONDS));
                var second = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, prepared(), false); assertNotNull(second); second.commit();
                assertTrue(sql.await(5, TimeUnit.SECONDS)); verify(backend, times(2)).updateExistingGrave(any(), eq(false));
            } finally { release.countDown(); }
        }
    }
    @Test void throwingDiagnosticDoesNotBreakOtherIdentityAcknowledgments() throws Exception {
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class);
            var journal = new SqlRecoveryJournal(io, backend, directory, ignored -> { throw new AssertionError("synthetic diagnostic failure"); });
            assertTrue(await(journal.loaded()));
            doThrow(new IllegalStateException("synthetic outage")).when(backend).upsertGrave(any(), anyBoolean());
            var first = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, prepared(), false);
            var second = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, prepared(), false);
            first.commit(); assertTrue(await(second.commit()));
            assertThrows(ExecutionException.class, () -> await(first.commit()));
        }
    }
    @Test void oversizeStringIsRejectedWithoutLeakingAnAdmission() throws Exception {
        try (var io = new FeatureIO(1)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend); var original = prepared();
            var large = new GravesConfig(original.getUuid(), "Synthetic", original.getLocation(), new HashMap<>(), 0,
                    "x".repeat(1_048_577), original.getTime(), false, 0, null, null, "CHEST");
            large.setWorldId(original.getWorldId());
            assertNull(journal.reserve(SqlRecoveryJournal.Kind.DISPLAY, large, false));
            await(io.barrier()); verifyNoInteractions(backend);
        }
    }
    @Test void boundedPassesContinueDurableAdmissionWithoutBusyRetryingAnOutage() throws Exception {
        try (var io = new FeatureIO(64)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend);
            var calls = new AtomicInteger();
            doAnswer(call -> { calls.incrementAndGet(); throw new IllegalStateException("synthetic outage"); })
                    .when(backend).upsertGrave(any(), anyBoolean());
            var release = new CountDownLatch(1);
            var occupied = io.submit(() -> { try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } return true; });
            var tickets = new ArrayList<SqlRecoveryJournal.Reservation>();
            try {
                for (int i = 0; i < 33; i++) {
                    var ticket = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, prepared(), false); assertNotNull(ticket); tickets.add(ticket);
                }
                tickets.forEach(SqlRecoveryJournal.Reservation::commit);
            } finally { release.countDown(); tickets.forEach(SqlRecoveryJournal.Reservation::close); }
            await(occupied);
            for (var ticket : tickets) { assertEquals(SqlRecoveryJournal.Disposition.DURABLE, await(ticket.durable())); assertThrows(ExecutionException.class, () -> await(ticket.commit())); }
            assertEquals(33, calls.get(), "one outage must not create a self-scheduled retry loop"); journal.close();
        }
    }
    @Test void laterPublicationFailureCannotStrandKnownDurablePrefixAtShutdown() throws Exception {
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var occupied = io.submit(() -> { entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } return true; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var first = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, prepared(), false);
            var second = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, prepared(), true);
            try {
                Files.write(directory.resolve("0000000000000000002.sql.tmp"), new byte[]{1});
                first.commit(); second.commit(); journal.close();
            } finally { release.countDown(); first.close(); second.close(); }
            await(occupied);
            assertEquals(SqlRecoveryJournal.Disposition.DURABLE, await(first.durable()));
            assertEquals(SqlRecoveryJournal.Disposition.HELD, await(second.durable()));
            assertThrows(ExecutionException.class, () -> await(first.commit()));
            assertTrue(Files.exists(directory.resolve("0000000000000000001.sql")));
            verifyNoInteractions(backend);
        }
    }
    @Test void cancelledPreflightHasAnExplicitNonReplayableOutcome() throws Exception {
        try (var io = new FeatureIO(4)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend);
            var ticket = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, prepared(), false); ticket.close();
            assertEquals(SqlRecoveryJournal.Disposition.CANCELLED, await(ticket.durable())); assertFalse(await(ticket.commit()));
            verifyNoInteractions(backend);
        }
    }
    @Test void blockingLoadedObserverCannotPreventStartupReplay() throws Exception {
        try (var io = new FeatureIO(4)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend);
            doThrow(new IllegalStateException("synthetic outage")).when(backend).upsertGrave(any(), anyBoolean());
            var ticket = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, prepared(), false); ticket.commit(); await(ticket.durable()); journal.close();
        }
        try (var io = new FeatureIO(4)) {
            var releaseIo = new CountDownLatch(1); var releaseObserver = new CountDownLatch(1); var observer = new CountDownLatch(1); var sql = new CountDownLatch(1);
            var occupied = io.submit(() -> { try { assertTrue(releaseIo.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } return true; });
            var backend = mock(GraveStorageBackend.class);
            doAnswer(call -> { sql.countDown(); return null; }).when(backend).upsertGrave(any(), anyBoolean());
            var journal = new SqlRecoveryJournal(io, backend, directory, ignored -> {});
            journal.loaded().thenRun(() -> {
                observer.countDown(); try { assertTrue(releaseObserver.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
            });
            try {
                releaseIo.countDown(); assertTrue(observer.await(5, TimeUnit.SECONDS)); assertTrue(sql.await(5, TimeUnit.SECONDS));
            } finally { releaseIo.countDown(); releaseObserver.countDown(); journal.close(); }
            await(occupied);
        }
    }
    @Test void explicitRetryDuringContinuationIsNotAbsorbed() throws Exception {
        try (var io = new FeatureIO(64)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend); var firstRecord = prepared();
            var attempts = new AtomicInteger(); var recovered = new CountDownLatch(1); var release = new CountDownLatch(1);
            doAnswer(call -> {
                GravesConfig captured = call.getArgument(0);
                if (captured.getUuid().equals(firstRecord.getUuid())) {
                    if (attempts.incrementAndGet() == 1) { journal.wake(); throw new IllegalStateException("synthetic outage"); }
                    recovered.countDown();
                }
                return null;
            }).when(backend).upsertGrave(any(), anyBoolean());
            var occupied = io.submit(() -> { try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } return true; });
            var tickets = new ArrayList<SqlRecoveryJournal.Reservation>();
            try {
                for (int i = 0; i < 33; i++) {
                    var ticket = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, i == 0 ? firstRecord : prepared(), false);
                    assertNotNull(ticket); tickets.add(ticket);
                }
                tickets.forEach(SqlRecoveryJournal.Reservation::commit);
            } finally { release.countDown(); tickets.forEach(SqlRecoveryJournal.Reservation::close); }
            await(occupied); assertTrue(recovered.await(5, TimeUnit.SECONDS)); assertEquals(2, attempts.get()); journal.close();
        }
    }
}
