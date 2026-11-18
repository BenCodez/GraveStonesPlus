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
            encoding.when(() -> InventorySerializer.encode(any())).thenAnswer(call -> { assertFalse(io.isOwnerThread()); java.util.Map<Integer,ItemStack> items = call.getArgument(0); return "synthetic:" + (items.isEmpty() ? 0 : items.get(0).getAmount()); });
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
    @Test void bulkCapacityRejectionDoesNotConsumeCallerDataOrWriteRows() throws Exception {
        try (var h = new Fixture(1,null)) {
            var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
            var busy = h.io.submit(() -> { entered.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); } return true; });
            assertTrue(entered.await(5,TimeUnit.SECONDS)); var queued = h.io.submit(() -> true);
            var records = List.of(record(1),record(2),record(3));
            try { assertThrows(ExecutionException.class,()->await(h.manager.saveSnapshotFromObjects(records.stream().map(SqlManagerIntegrationTest::grave).toList(),List.of()))); }
            finally { release.countDown(); }
            await(busy); await(queued); assertEquals(0,h.journal.records()); verify(h.backend,never()).upsertGrave(any(),anyBoolean());
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
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={130,300})
    void legacyLargeSnapshotUsesOneAdmissionAndBoundedProgressThroughShutdown(int count) throws Exception {
        try (var h = new Fixture(128,null)) {
            var calls = new AtomicInteger(); var records = new ArrayList<GravesConfig>();
            for (int i=0;i<count;i++) records.add(record(i+1));
            doAnswer(call -> { assertTrue(h.io.isOwnerThread()); assertEquals("synthetic:4",SqlJournalHarness.encoded(call.getArgument(0))); calls.incrementAndGet(); return null; })
                    .when(h.backend).upsertGrave(any(),anyBoolean());
            var saved = h.manager.saveSnapshotFromObjects(records.stream().map(SqlManagerIntegrationTest::grave).toList(),List.of());
            records.forEach(record -> record.getItems().get(0).setAmount(99));
            h.manager.close(); h.io.close(); assertTrue(await(saved)); assertEquals(count,calls.get());
            assertTrue(h.io.isTerminated()); verify(h.backend,times(1)).close(); assertEquals(0,h.journal.records());
        }
    }
    @Test void coordinateEncodingErrorReleasesBothSingleAndBulkAdmissionsBeforeClose() throws Exception {
        try (var h = new Fixture(1,null)) {
            var broken = record(1); var location = mock(Location.class); when(location.clone()).thenReturn(location);
            when(location.getBlockX()).thenThrow(new AssertionError("synthetic coordinate encoding fault")); field(broken,"location",location);
            assertThrows(AssertionError.class,()->h.manager.reserveSqlTransition(SqlRecoveryJournal.Kind.DISPLAY,broken,false));
            assertFalse(h.io.hasUnsubmittedAdmissions());
            var failed = h.manager.saveSnapshotFromObjects(List.of(grave(record(2)),grave(broken)),List.of());
            var error = assertThrows(ExecutionException.class,()->await(failed)); assertInstanceOf(AssertionError.class,error.getCause());
            assertFalse(h.io.hasUnsubmittedAdmissions()); h.manager.close(); h.io.close();
            assertTrue(h.io.isTerminated()); verify(h.backend,times(1)).close(); verify(h.backend,never()).upsertGrave(any(),anyBoolean());
        }
    }
    @Test void failedBulkAndFollowingWriteStayDurableAndReplayInLogicalOrder() throws Exception {
        var source = record(1);
        try (var h = new Fixture(128,directory)) {
            h.encoding.when(() -> InventorySerializer.encode(any())).thenReturn("x".repeat(8192));
            var records = new ArrayList<Grave>(); for(int i=0;i<140;i++) records.add(grave(i==0 ? source : record(i+1)));
            assertThrows(ExecutionException.class,()->await(h.manager.saveSnapshotFromObjects(records,List.of())));
            source.setDestroyed(true);
            var following = h.manager.reserveSqlTransition(SqlRecoveryJournal.Kind.UPDATE,source,true); assertNotNull(following); following.commit();
            assertEquals(SqlRecoveryJournal.Disposition.DURABLE,await(following.durable()));
            assertThrows(ExecutionException.class,()->await(following.commit()));
            assertEquals(2,h.journal.records());
            try(var files=java.nio.file.Files.list(directory)) { assertTrue(files.anyMatch(path -> { try { return java.nio.file.Files.size(path)>1_048_576; } catch(Exception failure) { throw new AssertionError(failure); } })); }
        }
        try (var h = new Fixture(128,directory)) {
            assertThrows(ExecutionException.class,()->await(h.manager.loadMySQLForRestoration()));
            assertTrue(h.owners.isEmpty());
            var calls = new AtomicInteger(); var updated = new CountDownLatch(1);
            doAnswer(call -> { assertEquals(8192,SqlJournalHarness.encoded(call.getArgument(0)).length()); calls.incrementAndGet();return null; }).when(h.backend).upsertGrave(any(),anyBoolean());
            doAnswer(call -> { assertEquals(140,calls.get()); assertTrue(((GravesConfig)call.getArgument(0)).isDestroyed()); updated.countDown();return null; }).when(h.backend).updateExistingGrave(any(),eq(true));
            h.journal.journal.wake(); assertTrue(updated.await(5,TimeUnit.SECONDS)); assertEquals(140,calls.get());
        }
    }
    @Test void bulkByteBudgetRejectsAtomicallyAndReleasesTheSingleAdmission() throws Exception {
        try (var h = new Fixture(4,null)) {
            var constructor = SqlRecoveryJournal.class.getDeclaredConstructor(FeatureIO.class,GraveStorageBackend.class,Path.class,java.util.function.Consumer.class,int.class,long.class);
            constructor.setAccessible(true);
            var bounded = constructor.newInstance(h.io,h.backend,h.journal.directory.resolve("bounded"),(java.util.function.Consumer<String>)ignored->{},4,256L);
            assertTrue(await(bounded.loaded())); field(h.manager,"sqlJournal",bounded);
            var first = record(1); var second = record(2);
            assertThrows(ExecutionException.class,()->await(h.manager.saveSnapshotFromObjects(List.of(grave(first),grave(second)),List.of())));
            assertFalse(h.io.hasUnsubmittedAdmissions()); verify(h.backend,never()).upsertGrave(any(),anyBoolean());
            assertEquals(4,first.getItems().get(0).getAmount()); assertEquals(4,second.getItems().get(0).getAmount());
        }
    }
    @Test void largeSnapshotYieldsToAlreadyQueuedFeatureWork() throws Exception {
        try (var h = new Fixture(8,null)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
            doAnswer(call -> {
                if(calls.incrementAndGet()==1) { entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); }
                return null;
            }).when(h.backend).upsertGrave(any(),anyBoolean());
            var rows = new ArrayList<Grave>(); for(int i=0;i<130;i++) rows.add(grave(record(i+1)));
            var saved = h.manager.saveSnapshotFromObjects(rows,List.of());
            try {
                assertTrue(entered.await(5,TimeUnit.SECONDS));
                var unrelated = h.io.submit(() -> { assertTrue(calls.get()<130,"bulk must yield before monopolizing the full snapshot"); return true; });
                release.countDown(); assertTrue(await(unrelated)); assertTrue(await(saved));
            } finally { release.countDown(); }
        }
    }
    @Test void outageReleasesAdmissionsForEveryHeldBulkSuccessor() throws Exception {
        try (var h = new Fixture(8,null)) {
            var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
            var busy = h.io.submit(() -> { entered.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new AssertionError(e); } return true; });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            doThrow(new IllegalStateException("synthetic outage")).when(h.backend).upsertGrave(any(),anyBoolean());
            var first = h.manager.saveSnapshotFromObjects(List.of(grave(record(1))),List.of());
            var second = h.manager.saveSnapshotFromObjects(List.of(grave(record(2))),List.of());
            try {
                release.countDown(); await(busy);
                assertThrows(ExecutionException.class,()->await(first)); assertThrows(ExecutionException.class,()->await(second));
                assertFalse(h.io.hasUnsubmittedAdmissions()); assertEquals(2,h.journal.records());
            } finally {
                release.countDown();
                if(h.io.hasUnsubmittedAdmissions()) await(h.io.submit(() -> {
                    try { var retire=SqlRecoveryJournal.class.getDeclaredMethod("retireAfterDrain"); retire.setAccessible(true); retire.invoke(h.journal.journal); }
                    catch(ReflectiveOperationException error) { throw new AssertionError(error); }
                    return true;
                }));
            }
            h.manager.close(); h.io.close(); assertTrue(h.io.isTerminated()); verify(h.backend,times(1)).close();
        }
    }
    @Test void closingFailedSingleBeforeBulkSettlesBothWithoutStarvingShutdown() throws Exception {
        try (var h = new Fixture(8,null)) {
            var release = new CountDownLatch(1); var entered = new CountDownLatch(1);
            var busy = h.io.submit(() -> { entered.countDown(); try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new AssertionError(e); } return true; });
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            doThrow(new IllegalStateException("synthetic persistent outage")).when(h.backend).updateExistingGrave(any(),anyBoolean());
            var ticket = h.manager.reserveSqlTransition(SqlRecoveryJournal.Kind.UPDATE,record(1),false); assertNotNull(ticket);
            var single = ticket.commit();
            var bulk = h.manager.saveSnapshotFromObjects(List.of(grave(record(2))),List.of());
            h.manager.close();
            try {
                release.countDown(); await(busy);
                assertThrows(ExecutionException.class,()->await(single));
                assertThrows(ExecutionException.class,()->await(bulk));
                assertTrue(h.closed.await(5,TimeUnit.SECONDS));
                assertFalse(h.io.hasUnsubmittedAdmissions());
                verify(h.backend,never()).upsertGrave(any(),anyBoolean());
                assertEquals(2,h.journal.records());
            } finally {
                release.countDown();
                if(h.io.hasUnsubmittedAdmissions()) await(h.io.submit(() -> {
                    try { var retire=SqlRecoveryJournal.class.getDeclaredMethod("retireAfterDrain"); retire.setAccessible(true); retire.invoke(h.journal.journal); }
                    catch(ReflectiveOperationException error) { throw new AssertionError(error); }
                    return true;
                }));
            }
            h.io.close(); assertTrue(h.io.isTerminated()); verify(h.backend,times(1)).close();
        }
    }

    @Test void inlineWorkerCompletionCannotOvertakeCommitDrainRequestDuringClose() throws Exception {
        try(var h=new Fixture(4,null)) {
            var proxy=mock(FeatureIO.class,org.mockito.AdditionalAnswers.delegatesTo(h.io));
            doAnswer(call->{
                var original=h.io.admit();if(original==null)return null;
                var wrapped=mock(FeatureIO.Admission.class);
                doAnswer(unused->{original.close();return null;}).when(wrapped).close();
                doAnswer(submit->{
                    java.util.function.Supplier<Boolean> action=submit.getArgument(0);
                    var result=original.submit(action);
                    // Force the worker notification to finish before submit returns to commit.
                    await(result);return result;
                }).when(wrapped).submit(any());
                return wrapped;
            }).when(proxy).admit();
            field(h.journal.journal,"io",proxy);
            var ticket=h.manager.reserveSqlTransition(SqlRecoveryJournal.Kind.UPDATE,record(1),true);assertNotNull(ticket);
            h.manager.close();await(h.io.barrier());verify(h.backend,never()).close();
            assertTrue(await(ticket.commit()));assertTrue(h.closed.await(5,TimeUnit.SECONDS));
            verify(h.backend,times(1)).updateExistingGrave(any(),eq(true));verify(h.backend,times(1)).close();
        }
    }

    @Test void cancellingRecreationBarrierWakesAcceptedSuffixBeforeFinalAdmissionRelease() throws Exception {
        try(var h=new Fixture(4,null)) {
            var proxy=mock(FeatureIO.class,org.mockito.AdditionalAnswers.delegatesTo(h.io));
            doAnswer(call->{
                var original=h.io.admit();if(original==null)return null;
                var wrapped=mock(FeatureIO.Admission.class,org.mockito.AdditionalAnswers.delegatesTo(original));
                doAnswer(unused->{original.close();assertTrue(h.closed.await(5,TimeUnit.SECONDS));return null;}).when(wrapped).close();
                return wrapped;
            }).when(proxy).admit();
            field(h.journal.journal,"io",proxy);var record=record(1);
            try(var barrier=h.journal.journal.reserveRecreation(record);var following=h.manager.reserveSqlTransition(SqlRecoveryJournal.Kind.UPDATE,record,true)) {
                assertNotNull(barrier);assertNotNull(following);following.commit();h.manager.close();await(h.io.barrier());
                verify(h.backend,never()).close();verify(h.backend,never()).updateExistingGrave(any(),anyBoolean());
                barrier.close();assertFalse(await(barrier.commit()));assertTrue(await(following.commit()));
                assertEquals(SqlRecoveryJournal.Disposition.CANCELLED,await(barrier.durable()));
                verify(h.backend,times(1)).updateExistingGrave(any(),eq(true));verify(h.backend,times(1)).close();
            }
        }
    }

}
