package com.bencodez.gravestonesplus.tests.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.gravestonesplus.graves.FeatureIO;
import com.bencodez.gravestonesplus.storage.*;

class SqlCreationOwnershipTest {
    @TempDir Path directory;
    static <T> T await(CompletableFuture<T> f) throws Exception { return f.get(5, TimeUnit.SECONDS); }
    private SqlRecoveryJournal journal(FeatureIO io, GraveStorageBackend backend, Path path) throws Exception {
        var journal = new SqlRecoveryJournal(io, backend, path, ignored -> {}); io.onDrain(journal::drain);
        assertTrue(await(journal.loaded())); return journal;
    }
    @Test void finalUuidIsChosenAfterAdmissionAndOneFrozenUpsertSurvivesRestart() throws Exception {
        UUID actual = UUID.randomUUID(); var record = SqlRecoveryJournalTest.prepared(); record.setGraveDisplayType("DISPLAY_ENTITY");
        var writes = new java.util.concurrent.CopyOnWriteArrayList<GravesConfig>();
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); doThrow(new IllegalStateException("synthetic outage")).when(backend).upsertGrave(any(), anyBoolean());
            var journal = journal(io, backend, directory); var slot = journal.reserveCreation(record); assertNotNull(slot);
            record.getItems().clear(); record.setDestroyed(true); record.setDisplayUUID(UUID.randomUUID());
            assertTrue(slot.acceptCreation()); assertFalse(slot.prepareCreation(null, null, false));
            assertTrue(slot.prepareCreation(actual, null, false)); assertFalse(slot.prepareCreation(UUID.randomUUID(), null, false));
            slot.close(); // accepted ownership cannot be cancelled by cleanup/observer retirement
            slot.commit(); assertEquals(SqlRecoveryJournal.Disposition.DURABLE, await(slot.durable()));
            await(io.barrier()); journal.close();
        }
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); doAnswer(call -> { writes.add(call.getArgument(0)); return null; }).when(backend).upsertGrave(any(), eq(false));
            var journal = journal(io, backend, directory); await(io.barrier());
            assertEquals(1, writes.size()); var saved = writes.getFirst(); assertEquals(actual, saved.getDisplayUUID());
            assertFalse(saved.isDestroyed()); assertEquals(17, saved.getExp()); assertEquals(record.getUuid(), saved.getUuid());
            var field = GravesConfig.class.getDeclaredField("preparedItems"); field.setAccessible(true); assertEquals("synthetic-encoded", field.get(saved));
            journal.close();
        }
    }
    @Test void unusedReservationCancelsAndCannotBecomeAReplayableCreation() throws Exception {
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend, directory);
            var slot = journal.reserveCreation(SqlRecoveryJournalTest.prepared()); assertNotNull(slot); slot.close();
            assertFalse(await(slot.commit())); assertEquals(SqlRecoveryJournal.Disposition.CANCELLED, await(slot.durable()));
            verifyNoInteractions(backend); journal.close();
        }
    }
    @Test void acceptedRamOnlyHeldBlocksUnloadAndSuccessorsUntilExactPublicationRepair() throws Exception {
        Path root = directory.resolve("installation"), path = root.resolve("sql"); UUID actual = UUID.randomUUID();
        var io = new FeatureIO(8, root); assertTrue(await(io.ready())); var backend = mock(GraveStorageBackend.class);
        var journal = journal(io, backend, path); var record = SqlRecoveryJournalTest.prepared(); record.setGraveDisplayType("DISPLAY_ENTITY");
        var slot = journal.reserveCreation(record); assertTrue(slot.acceptCreation()); assertTrue(slot.prepareCreation(actual, null, false));
        Files.delete(path); Files.writeString(path, "synthetic obstruction");
        assertThrows(ExecutionException.class, () -> await(slot.commit())); assertEquals(SqlRecoveryJournal.Disposition.HELD, await(slot.durable()));
        journal.close(); var closed = new CompletableFuture<Void>();
        Thread closer = new Thread(() -> { try { io.close(); closed.complete(null); } catch (Throwable e) { closed.completeExceptionally(e); } }, "synthetic-unload"); closer.start();
        try {
            assertThrows(TimeoutException.class, () -> closed.get(100, TimeUnit.MILLISECONDS));
            try (var successor = new FeatureIO(4, root)) { assertFalse(await(successor.ready())); }
            verifyNoInteractions(backend);
        } finally {
            Files.delete(path); Files.createDirectory(path); await(closed); closer.join(1000); assertFalse(closer.isAlive());
        }
        try (var next = new FeatureIO(8, root)) {
            assertTrue(await(next.ready())); var recovered = mock(GraveStorageBackend.class); var reopened = journal(next, recovered, path);
            await(next.barrier()); var capture = org.mockito.ArgumentCaptor.forClass(GravesConfig.class);
            verify(recovered, times(1)).upsertGrave(capture.capture(), eq(false)); assertEquals(actual, capture.getValue().getDisplayUUID()); reopened.close();
        }
        verifyNoInteractions(backend); // the repair path never retried JDBC under the old fatal generation
    }
    @Test void creationRemainsOrderedBeforeLaterRemoval() throws Exception {
        var calls = new java.util.concurrent.CopyOnWriteArrayList<String>();
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class);
            doAnswer(call -> { calls.add("creation"); return null; }).when(backend).upsertGrave(any(), eq(false));
            doAnswer(call -> { calls.add("remove"); return null; }).when(backend).setBroken(any(), eq(true));
            var journal = journal(io, backend, directory); var record = SqlRecoveryJournalTest.prepared();
            var creation = journal.reserveCreation(record); var removal = journal.reserve(SqlRecoveryJournal.Kind.FLAG, record, true);
            removal.commit(); await(io.barrier()); assertTrue(calls.isEmpty());
            assertTrue(creation.acceptCreation()); assertTrue(creation.prepareCreation(null, null, true));
            assertTrue(await(creation.commit())); await(io.barrier()); assertEquals(List.of("creation", "remove"), calls); journal.close();
        }
    }
}
