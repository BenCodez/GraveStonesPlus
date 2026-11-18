package com.bencodez.gravestonesplus.tests.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.*;
import com.bencodez.gravestonesplus.storage.*;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;

class SqlManagerIntegrationTest {
    @TempDir Path directory;
    static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    static void field(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    static GravesConfig record(int x) {
        var record = new GravesConfig(UUID.randomUUID(), "Synthetic", new Location(null,x,64,1),
                new HashMap<>(Map.of(0,new ItemStack(Material.EMERALD,4))),17,"synthetic",1000+x,false,0,null,null,"CHEST");
        record.setWorldId(UUID.randomUUID()); return record;
    }
    static Grave grave(GravesConfig record) { var grave = mock(Grave.class); when(grave.getGravesConfig()).thenReturn(record); return grave; }
    static final class Fixture implements AutoCloseable {
        final FeatureIO io;
        final GraveStonesPlus plugin = mock(GraveStonesPlus.class);
        final MySQLGraveStorageBackend backend = mock(MySQLGraveStorageBackend.class,CALLS_REAL_METHODS);
        final GraveStorageManager manager;
        final SqlJournalHarness journal;
        final BlockingQueue<Runnable> owners = new LinkedBlockingQueue<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final org.mockito.MockedStatic<InventorySerializer> encoding = mockStatic(InventorySerializer.class);
        Fixture(int capacity, Path path) throws Exception {
            io = new FeatureIO(capacity);
            encoding.when(() -> InventorySerializer.encode(any())).thenAnswer(call -> { assertFalse(io.isOwnerThread()); return "synthetic-encoded"; });
            when(plugin.getFeatureIO()).thenReturn(io); when(plugin.isEnabled()).thenReturn(true);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            var scheduler = mock(BukkitScheduler.class); when(plugin.getBukkitScheduler()).thenReturn(scheduler);
            doAnswer(call -> { owners.add(call.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin),any(Runnable.class));
            doNothing().when(backend).upsertGrave(any(),anyBoolean()); doNothing().when(backend).updateExistingGrave(any(),anyBoolean());
            doNothing().when(backend).setBroken(any(),anyBoolean()); doNothing().when(backend).saveDisplayIdentity(any());
            doNothing().when(backend).saveRecreatedGrave(any());
            doAnswer(call -> { assertTrue(io.isOwnerThread()); closed.countDown(); return null; }).when(backend).close();
            manager = new GraveStorageManager(plugin); field(manager,"backend",backend); field(manager,"type",DataStorageType.MYSQL);
            if (path != null) doThrow(new IllegalStateException("synthetic startup outage")).when(backend).upsertGrave(any(),anyBoolean());
            journal = path == null ? new SqlJournalHarness(io,backend,manager) : new SqlJournalHarness(io,backend,manager,path);
        }
        @Override public void close() throws Exception { try { journal.close(); } finally { encoding.close(); } }
    }
    @Test void finalDrainWaitsForReservedTicketThenCommitsAndClosesExactlyOnce() throws Exception {
        try (var h = new Fixture(4,null)) {
            var ticket = h.manager.reserveSqlTransition(SqlRecoveryJournal.Kind.UPSERT,record(1),false); assertNotNull(ticket);
            try {
                h.manager.close(); await(h.io.barrier()); verify(h.backend,never()).close();
                assertTrue(await(ticket.commit())); assertTrue(h.closed.await(5,TimeUnit.SECONDS));
                h.journal.journal.wake(); await(h.io.barrier());
                verify(h.backend,times(1)).upsertGrave(any(),eq(false)); verify(h.backend,times(1)).close();
            } finally { ticket.close(); }
        }
    }
    @Test void unusedReservationCancellationAlsoTriggersFinalClose() throws Exception {
        try (var h = new Fixture(4,null)) {
            var ticket = h.manager.reserveSqlTransition(SqlRecoveryJournal.Kind.UPDATE,record(1),true); assertNotNull(ticket);
            try { h.manager.close(); await(h.io.barrier()); verify(h.backend,never()).close(); }
            finally { ticket.close(); }
            assertEquals(SqlRecoveryJournal.Disposition.CANCELLED,await(ticket.durable()));
            assertTrue(h.closed.await(5,TimeUnit.SECONDS)); verify(h.backend,never()).updateExistingGrave(any(),anyBoolean());
        }
    }
    @Test void bulkCapacityRejectionCancelsAllRowsBeforeAnySqlMutation() throws Exception {
        try (var h = new Fixture(1,null)) {
            var records = List.of(record(1),record(2),record(3));
            assertThrows(ExecutionException.class,()->await(h.manager.saveSnapshotFromObjects(records.stream().map(SqlManagerIntegrationTest::grave).toList(),List.of())));
            await(h.io.barrier()); assertEquals(0,h.journal.records());
            verify(h.backend,never()).upsertGrave(any(),anyBoolean());
            for (var record : records) assertEquals(4,record.getItems().get(0).getAmount());
        }
    }
    @Test void bulkFailureRetiresOnlyConfirmedRowsAndRetriesOnlyTheRemainingIdentity() throws Exception {
        try (var h = new Fixture(8,null)) {
            var first = record(1); var second = record(2); var firstCalls = new AtomicInteger(); var secondCalls = new AtomicInteger();
            var retry = new CountDownLatch(1); var available = new AtomicBoolean(false);
            doAnswer(call -> {
                GravesConfig captured = call.getArgument(0);
                if (captured.getUuid().equals(first.getUuid())) firstCalls.incrementAndGet();
                else { secondCalls.incrementAndGet(); if (!available.get()) throw new IllegalStateException("synthetic outage"); retry.countDown(); }
                return null;
            }).when(h.backend).upsertGrave(any(),anyBoolean());
            assertThrows(ExecutionException.class,()->await(h.manager.saveSnapshotFromObjects(List.of(grave(first),grave(second)),List.of())));
            assertEquals(1,h.journal.records()); available.set(true); h.journal.journal.wake(); assertTrue(retry.await(5,TimeUnit.SECONDS));
            assertEquals(1,firstCalls.get()); assertEquals(2,secondCalls.get());
        }
    }
    @Test void restorationReadWaitsForJournalReplayAndNeverExposesUnreconciledRows() throws Exception {
        GravesConfig stored = record(1);
        try (var io = new FeatureIO(8); var encoding = mockStatic(InventorySerializer.class)) {
            encoding.when(()->InventorySerializer.encode(any())).thenReturn("synthetic-encoded");
            var backend = mock(GraveStorageBackend.class); doThrow(new IllegalStateException("synthetic outage")).when(backend).upsertGrave(any(),anyBoolean());
            var journal = new SqlRecoveryJournal(io,backend,directory,ignored->{}); assertTrue(await(journal.loaded()));
            var creation = journal.reserve(SqlRecoveryJournal.Kind.UPSERT,GraveStorageManager.snapshot(stored),false);
            var removed = GraveStorageManager.snapshot(stored); removed.setDestroyed(true);
            var removal = journal.reserve(SqlRecoveryJournal.Kind.UPDATE,removed,true);
            creation.commit(); removal.commit(); assertEquals(SqlRecoveryJournal.Disposition.DURABLE,await(removal.durable())); journal.close();
        }
        try (var h = new Fixture(8,directory)) {
            assertEquals(2,h.journal.records(), "outage must retain both startup obligations");
            assertThrows(ExecutionException.class,()->await(h.manager.loadMySQLForRestoration())); assertTrue(h.owners.isEmpty());
            var order = new CopyOnWriteArrayList<String>();
            doAnswer(call->{order.add("insert");return null;}).when(h.backend).upsertGrave(any(),anyBoolean());
            doAnswer(call->{ assertTrue(((GravesConfig)call.getArgument(0)).isDestroyed()); order.add("update");return null;}).when(h.backend).updateExistingGrave(any(),anyBoolean());
            var loaded = h.manager.loadMySQLForRestoration(); var decode = h.owners.poll(5,TimeUnit.SECONDS); assertNotNull(decode); decode.run();
            assertTrue(await(loaded).active().isEmpty()); assertEquals(List.of("insert","update"),order);
        }
    }
    @Test void startupFenceAndCloseRejectNewReservationsBeforeGameplayChanges() throws Exception {
        try (var h = new Fixture(4,null)) {
            h.manager.beginRestoration(); assertNull(h.manager.reserveSqlTransition(SqlRecoveryJournal.Kind.UPSERT,record(1),false));
            h.manager.finishRestorationRetention(); h.manager.close();
            assertNull(h.manager.reserveSqlTransition(SqlRecoveryJournal.Kind.UPSERT,record(1),false));
            assertThrows(ExecutionException.class,()->await(h.manager.loadMySQLForRestoration()));
            verify(h.backend,never()).upsertGrave(any(),anyBoolean());
        }
    }
    @Test void backendCloseErrorCannotAbandonTheFinalFeatureAcknowledgment() throws Exception {
        try (var h = new Fixture(4,null)) {
            doThrow(new AssertionError("synthetic pool close failure")).when(h.backend).close();
            h.manager.close(); await(h.io.barrier()); h.io.close();
            assertTrue(h.io.isTerminated()); verify(h.backend,times(1)).close();
        }
    }
    @Test void partialSqlStartupWithoutJournalStillClosesTheInitializedBackend() throws Exception {
        try (var h = new Fixture(4,null)) {
            field(h.manager,"sqlJournal",null); h.manager.close();
            assertTrue(h.closed.await(5,TimeUnit.SECONDS)); h.io.close();
            verify(h.backend,times(1)).close(); assertTrue(h.io.isTerminated());
        }
    }
}
