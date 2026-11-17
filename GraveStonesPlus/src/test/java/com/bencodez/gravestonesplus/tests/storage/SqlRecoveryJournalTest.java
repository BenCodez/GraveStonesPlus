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
        io.onDrain(journal::drain); assertTrue(await(journal.loaded())); await(io.barrier()); return journal;
    }
    @Test void rejectedStandaloneUpdateNeverAcquiresInsertAuthority() throws Exception {
        try (var io = new FeatureIO(4)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend);
            var record = prepared(); var ticket = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, true);
            assertNotNull(ticket); assertTrue(await(ticket.commit())); await(io.barrier());
            verify(backend).updateExistingGrave(same(record), eq(true)); verify(backend, never()).upsertGrave(any(), anyBoolean());
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
            verify(backend).saveDisplayIdentity(same(record)); verify(backend).setBroken(same(record), eq(true));
            verify(backend, never()).upsertGrave(any(), anyBoolean());
        }
    }
    @Test void reservationsHaveAnActualEntryCapAndReleaseWithoutWriting() throws Exception {
        try (var io = new FeatureIO(300)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend); var record = prepared();
            var held = new ArrayList<SqlRecoveryJournal.Reservation>();
            for (int i = 0; i < 256; i++) { var reservation = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, true); assertNotNull(reservation); held.add(reservation); }
            assertNull(journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, true));
            held.removeFirst().close(); var replacement = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, true); assertNotNull(replacement);
            replacement.close(); held.forEach(SqlRecoveryJournal.Reservation::close); await(io.barrier());
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
            var unavailable = new AtomicBoolean(true); var operations = new CopyOnWriteArrayList<String>();
            doAnswer(call -> { if (unavailable.get()) throw new IllegalStateException("synthetic outage"); operations.add("display"); return null; })
                    .when(backend).saveDisplayIdentity(any());
            doAnswer(call -> { operations.add("flag"); return null; }).when(backend).setBroken(any(), eq(true));
            var display = journal.reserve(SqlRecoveryJournal.Kind.DISPLAY, record, false);
            var flag = journal.reserve(SqlRecoveryJournal.Kind.FLAG, record, true);
            display.commit(); flag.commit(); await(io.barrier()); assertTrue(operations.isEmpty());
            unavailable.set(false); journal.wake(); await(io.barrier());
            assertEquals(List.of("display", "flag"), operations);
            verify(backend, never()).upsertGrave(any(), anyBoolean()); verify(backend, never()).updateExistingGrave(any(), anyBoolean());
        }
    }
    @Test void heldPredecessorBlocksOnlyItsOwnIdentityAndReleaseWakesSuccessor() throws Exception {
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend); var record = prepared();
            var held = journal.reserve(SqlRecoveryJournal.Kind.UPDATE, record, false);
            var later = journal.reserve(SqlRecoveryJournal.Kind.FLAG, record, true);
            later.commit(); await(io.barrier()); verifyNoInteractions(backend);
            held.close(); await(io.barrier()); assertTrue(await(later.commit()));
            verify(backend).setBroken(same(record), eq(true));
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
}
