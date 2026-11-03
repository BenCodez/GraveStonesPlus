package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraveReminderDeliveryTest {
    @TempDir Path directory;
    static GraveReminderStore.Notice notice(long deadline) { return new GraveReminderStore.Notice("player/grave/expiry/" + deadline, deadline, "expires soon"); }
    @Test void sendsOneSummaryThenPersistsAndSuppressesRestartDuplicates() throws Exception {
        Path file = directory.resolve("reminders.yml"); AtomicInteger sends = new AtomicInteger();
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, file, 8192); var store = new GraveReminderStore(state); FeaturePersistenceTest.await(store.loaded());
            assertEquals(2, store.deliver(List.of(notice(200), notice(300)), 100, fresh -> {
                assertTrue(state.snapshot().isEmpty()); assertEquals(2, fresh.size()); sends.incrementAndGet(); return true;
            }).size());
            assertTrue(store.deliver(List.of(notice(200)), 100, fresh -> { fail("same-process replay"); return true; }).isEmpty());
            FeaturePersistenceTest.await(io.barrier()); assertEquals(2, state.snapshot().size());
        }
        try (FeatureIO io = new FeatureIO()) {
            var store = new GraveReminderStore(new DurableFeatureState(io, file, 8192)); FeaturePersistenceTest.await(store.loaded());
            assertTrue(store.deliver(List.of(notice(200)), 100, fresh -> { fail("restart replay"); return true; }).isEmpty());
        }
        assertEquals(1, sends.get());
    }
    @Test void offlineOrRejectedDeliveryNeverMarksAndLaterLoginRemainsEligible() throws Exception {
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, new FeaturePersistenceTest.Disk(), 8192); var store = new GraveReminderStore(state); FeaturePersistenceTest.await(store.loaded());
            assertTrue(store.deliver(List.of(notice(200)), 100, fresh -> false).isEmpty());
            FeaturePersistenceTest.await(io.barrier()); assertTrue(state.snapshot().isEmpty());
            assertEquals(1, store.deliver(List.of(notice(200)), 100, fresh -> true).size());
        }
    }
    @Test void changedDeadlinesAreNewAndElapsedDeadlinesReclaimCapacity() throws Exception {
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, new FeaturePersistenceTest.Disk(), 1); var store = new GraveReminderStore(state); FeaturePersistenceTest.await(store.loaded());
            assertEquals(1, store.deliver(List.of(notice(200)), 100, fresh -> true).size()); FeaturePersistenceTest.await(io.barrier());
            assertEquals(1, store.deliver(List.of(notice(300)), 201, fresh -> true).size()); FeaturePersistenceTest.await(io.barrier());
            assertEquals(Set.of(notice(300).key()), state.snapshot().keySet());
        }
    }
    @Test void failedPersistenceNeverPretendsDurableAndSuppressesLocalRepeat() throws Exception {
        try (FeatureIO io = new FeatureIO()) {
            var disk = new FeaturePersistenceTest.Disk(); var state = new DurableFeatureState(io, disk, 8192); var store = new GraveReminderStore(state); FeaturePersistenceTest.await(store.loaded()); disk.fail = true;
            assertEquals(1, store.deliver(List.of(notice(200)), 100, fresh -> true).size()); FeaturePersistenceTest.await(io.barrier());
            assertTrue(state.snapshot().isEmpty()); assertFalse(state.isReady());
            assertTrue(store.deliver(List.of(notice(200)), 100, fresh -> { fail("duplicate after failure"); return true; }).isEmpty());
        }
    }
    @Test void disabledZeroInvalidAndLegacyMinuteTimerEligibilityMatchesProduction() {
        assertEquals(0, GraveDeadlines.expiry(100, "0")); assertEquals(0, GraveDeadlines.expiry(100, ""));
        assertEquals(0, GraveDeadlines.expiry(100, "invalid")); assertEquals(120100, GraveDeadlines.expiry(100, "2"));
        assertEquals(120100, GraveDeadlines.expiry(100, "2m")); assertEquals(0, GraveDeadlines.deadline(Long.MAX_VALUE, 1));
        assertFalse(GraveDeadlines.eligible(100, 0, 100)); assertFalse(GraveDeadlines.eligible(100, 200, 0));
        assertFalse(GraveDeadlines.eligible(200, 200, 100)); assertFalse(GraveDeadlines.eligible(99, 200, 100));
        assertTrue(GraveDeadlines.eligible(100, 200, 100));
    }
    @Test void oldReminderFileAndDeadlineIdentityRemainReadable() throws Exception {
        Path file = directory.resolve("reminders.yml"); Files.writeString(file, "sent:\n- player/grave/expiry/200\n");
        try (FeatureIO io = new FeatureIO()) {
            var store = new GraveReminderStore(new DurableFeatureState(io, file, 8192)); assertTrue(FeaturePersistenceTest.await(store.loaded()));
            assertTrue(store.deliver(List.of(notice(200)), 100, fresh -> { fail("legacy replay"); return true; }).isEmpty());
            assertEquals(1, store.deliver(List.of(notice(201)), 100, fresh -> true).size());
        }
    }
    @Test void fullWorkerNeverSendsWithoutPublicationAdmission() throws Exception {
        try (FeatureIO io = new FeatureIO(1)) {
            var state = new DurableFeatureState(io, new FeaturePersistenceTest.Disk(), 8192);
            var store = new GraveReminderStore(state); FeaturePersistenceTest.await(store.loaded());
            var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
            var busy = io.submit(() -> { entered.countDown(); try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); } return true; });
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)); var queued = io.submit(() -> true);
            assertTrue(store.deliver(List.of(notice(200)), 100, fresh -> { fail("sent without admitted persistence"); return true; }).isEmpty());
            release.countDown(); FeaturePersistenceTest.await(busy); FeaturePersistenceTest.await(queued);
            assertEquals(1, store.deliver(List.of(notice(200)), 100, fresh -> true).size());
        }
    }

}
