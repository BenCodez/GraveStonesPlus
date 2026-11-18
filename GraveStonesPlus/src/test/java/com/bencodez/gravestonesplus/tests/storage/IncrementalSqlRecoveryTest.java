package com.bencodez.gravestonesplus.tests.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.FeatureIO;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.storage.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IncrementalSqlRecoveryTest {
    static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    static void field(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    static GravesConfig record(int x) {
        var record = new GravesConfig(UUID.randomUUID(), "Synthetic", new Location(null, x, 64, 1),
                new HashMap<>(Map.of(0, new ItemStack(Material.STONE, 2))), 17, "synthetic", 1000L + x,
                false, 0, null, null, "CHEST");
        record.setWorldId(UUID.randomUUID()); return record;
    }
    static final class Fixture implements AutoCloseable {
        final Thread owner = Thread.currentThread();
        final FeatureIO io;
        final GraveStonesPlus plugin = mock(GraveStonesPlus.class);
        final GraveStorageBackend backend = mock(GraveStorageBackend.class);
        final GraveStorageManager manager;
        final SqlJournalHarness journal;
        final org.mockito.MockedStatic<InventorySerializer> encoding = mockStatic(InventorySerializer.class);
        final AtomicInteger encodes = new AtomicInteger();
        final GravesConfig target = record(1);
        Fixture(int capacity) throws Exception {
            io = new FeatureIO(capacity);
            encoding.when(() -> InventorySerializer.encode(any())).thenAnswer(call -> {
                assertSame(owner, Thread.currentThread(), "encoding must stay on the caller owner");
                encodes.incrementAndGet();
                java.util.Map<Integer, ItemStack> captured = call.getArgument(0);
                return "prepared:" + (captured.isEmpty() ? 0 : captured.get(0).getAmount());
            });
            when(plugin.getFeatureIO()).thenReturn(io);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            // Accessing these inventories simulates an unrelated Folia region.
            var foreign = mock(ItemStack.class);
            when(foreign.clone()).thenThrow(new AssertionError("unrelated grave inventory was captured"));
            var unrelated = record(2); unrelated.getItems().put(0, foreign);
            var grave = mock(Grave.class); when(grave.getGravesConfig()).thenReturn(unrelated);
            when(plugin.activeGraveSnapshot()).thenReturn(List.of(grave));
            when(plugin.brokenGraveSnapshot()).thenReturn(List.of(grave));
            manager = new GraveStorageManager(plugin);
            field(manager, "backend", backend); field(manager, "type", DataStorageType.MYSQL);
            journal = new SqlJournalHarness(io, backend, manager);
        }
        CompletableFuture<?> operation(String operation) {
            return switch (operation) {
                case "display" -> manager.saveDisplayIdentity(target);
                case "recreate" -> manager.saveRecreatedGrave(target);
                case "upsert" -> { manager.upsertGrave(target, false); yield CompletableFuture.completedFuture(null); }
                case "update" -> { manager.updateExistingGrave(target, true); yield CompletableFuture.completedFuture(null); }
                case "broken" -> { manager.setBroken(target, true); yield CompletableFuture.completedFuture(null); }
                default -> throw new AssertionError(operation);
            };
        }
        @Override public void close() throws Exception { try { journal.close(); } finally { encoding.close(); } }
    }
    @ParameterizedTest @ValueSource(strings = {"upsert", "update", "broken", "display", "recreate"})
    void healthyIncrementalWritesCaptureOnlyChangedInventory(String operation) throws Exception {
        try (var h = new Fixture(4)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var blocking = h.io.submit(() -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                return true;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var snapshots = new CopyOnWriteArrayList<GravesConfig>();
            org.mockito.stubbing.Answer<Object> capture = call -> {
                assertNotSame(h.owner, Thread.currentThread(), "SQL must stay on I/O owner");
                snapshots.add(call.getArgument(0)); return null;
            };
            doAnswer(capture).when(h.backend).upsertGrave(any(), anyBoolean());
            doAnswer(capture).when(h.backend).updateExistingGrave(any(), anyBoolean());
            doAnswer(capture).when(h.backend).setBroken(any(), anyBoolean());
            doAnswer(capture).when(h.backend).saveDisplayIdentity(any());
            doAnswer(capture).when(h.backend).saveRecreatedGrave(any());
            CompletableFuture<?> result;
            try {
                result = h.operation(operation);
                h.target.getItems().get(0).setAmount(99);
            } finally { release.countDown(); }
            await(blocking); await(result); await(h.io.barrier());
            assertEquals(1, snapshots.size()); if (operation.equals("upsert") || operation.equals("update"))
                assertEquals("prepared:2", SqlJournalHarness.encoded(snapshots.getFirst()));
            assertEquals(operation.equals("upsert") || operation.equals("update") ? 1 : 0, h.encodes.get(), "only full SQL mutations encode their own input");
            verify(h.plugin, never()).activeGraveSnapshot(); verify(h.plugin, never()).brokenGraveSnapshot();
        }
    }
    @Test void historicalStartupUpdatesHaveLinearEncodingCount() throws Exception {
        try (var h = new Fixture(256)) {
            List<GravesConfig> records = new ArrayList<>();
            List<Grave> history = new ArrayList<>();
            for (int i = 0; i < 129; i++) {
                var record = record(100 + i); record.setDestroyed(true); records.add(record);
                var grave = mock(Grave.class); when(grave.getGravesConfig()).thenReturn(record); history.add(grave);
            }
            when(h.plugin.activeGraveSnapshot()).thenReturn(List.of());
            when(h.plugin.brokenGraveSnapshot()).thenReturn(history);
            h.manager.beginRestoration(); h.manager.retainRestorationBatch(List.of(), records); h.manager.finishRestorationRetention();
            assertEquals(129, h.encodes.get()); h.encodes.set(0);
            var written = new CountDownLatch(129);
            doAnswer(call -> { written.countDown(); return null; }).when(h.backend).updateExistingGrave(any(), eq(true));
            for (var record : records) {
                h.manager.restorationResolved(record);
                h.manager.updateExistingGrave(record, true);
            }
            await(h.io.barrier());
            waitFor(written);
            assertEquals(129, h.encodes.get(), "each historical transition must encode only its own record");
            verify(h.backend, times(129)).updateExistingGrave(any(), eq(true));
            verify(h.plugin, never()).activeGraveSnapshot(); verify(h.plugin, never()).brokenGraveSnapshot();
        }
    }
    static void waitFor(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    @ParameterizedTest @ValueSource(strings = {"update", "display"})
    void failedOlderWriteCannotRetireNewerFullRecoveryCapture(String later) throws Exception {
        try (var h = new Fixture(4)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var latest = new CountDownLatch(1);
            var writes = new CopyOnWriteArrayList<String>(); var calls = new AtomicInteger();
            doAnswer(call -> {
                writes.add("insert:" + SqlJournalHarness.encoded(call.getArgument(0)));
                if (calls.incrementAndGet() == 1) { entered.countDown(); waitFor(release); throw new IllegalStateException("synthetic SQL failure"); }
                return null;
            }).when(h.backend).upsertGrave(any(), anyBoolean());
            doAnswer(call -> { writes.add("update:" + SqlJournalHarness.encoded(call.getArgument(0))); latest.countDown(); return null; })
                    .when(h.backend).updateExistingGrave(any(), eq(true));
            doAnswer(call -> { writes.add("display"); latest.countDown(); return null; }).when(h.backend).saveDisplayIdentity(any());
            h.manager.upsertGrave(h.target, false); waitFor(entered);
            try { h.target.setDestroyed(true); h.target.getItems().get(0).setAmount(7); h.operation(later); h.target.getItems().get(0).setAmount(99); }
            finally { release.countDown(); }
            waitFor(latest);
            assertEquals(List.of("insert:prepared:2", "insert:prepared:2", later.equals("update") ? "update:prepared:7" : "display"), writes);
            assertEquals(later.equals("update") ? 2 : 1, h.encodes.get());
            verify(h.plugin, never()).activeGraveSnapshot(); verify(h.plugin, never()).brokenGraveSnapshot();
        }
    }
    @Test void newOwnerTransitionDuringRecoverySurvivesOlderAcknowledgement() throws Exception {
        try (var h = new Fixture(4)) {
            var firstFailed = new CountDownLatch(1); var retryEntered = new CountDownLatch(1); var release = new CountDownLatch(1); var latest = new CountDownLatch(1);
            var calls = new AtomicInteger(); var writes = new CopyOnWriteArrayList<String>();
            doAnswer(call -> {
                writes.add("insert:" + SqlJournalHarness.encoded(call.getArgument(0)));
                int number = calls.incrementAndGet();
                if (number == 1) { firstFailed.countDown(); throw new IllegalStateException("synthetic SQL failure"); }
                if (number == 2) { retryEntered.countDown(); waitFor(release); }
                return null;
            }).when(h.backend).upsertGrave(any(), anyBoolean());
            doAnswer(call -> { writes.add("update:" + SqlJournalHarness.encoded(call.getArgument(0))); latest.countDown(); return null; })
                    .when(h.backend).updateExistingGrave(any(), eq(true));
            h.manager.upsertGrave(h.target, false); waitFor(firstFailed); h.journal.journal.wake(); waitFor(retryEntered);
            try { h.target.setDestroyed(true); h.target.getItems().get(0).setAmount(7); h.manager.updateExistingGrave(h.target, true); h.target.getItems().get(0).setAmount(99); }
            finally { release.countDown(); }
            waitFor(latest); assertEquals(List.of("insert:prepared:2", "insert:prepared:2", "update:prepared:7"), writes);
            assertEquals(2, h.encodes.get());
        }
    }
    @Test void saturatedAdmissionRejectsWithoutConsumingCallerContentsOrCreatingRows() throws Exception {
        try (var h = new Fixture(1)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var first = h.io.submit(() -> { entered.countDown(); waitFor(release); return true; }); waitFor(entered);
            var queued = h.io.submit(() -> true);
            try {
                assertThrows(RejectedExecutionException.class, () -> h.manager.upsertGrave(h.target, false));
                assertEquals(2, h.target.getItems().get(0).getAmount()); h.manager.close(); h.manager.close();
            } finally { release.countDown(); }
            await(first); await(queued); h.io.close();
            verify(h.backend, never()).upsertGrave(any(), anyBoolean()); verify(h.backend, times(1)).close(); assertEquals(0, h.journal.records());
            verify(h.plugin, never()).activeGraveSnapshot(); verify(h.plugin, never()).brokenGraveSnapshot();
        }
    }
    @Test void permanentFailureRetainsPreparedContentsAndClosesOnceWithoutBusyRetry() throws Exception {
        try (var h = new Fixture(4)) {
            var calls = new AtomicInteger();
            doAnswer(call -> {
                assertNotSame(h.owner, Thread.currentThread()); calls.incrementAndGet();
                assertEquals("prepared:2", SqlJournalHarness.encoded(call.getArgument(0)));
                throw new AssertionError("synthetic permanent backend failure");
            }).when(h.backend).upsertGrave(any(), anyBoolean());
            h.manager.upsertGrave(h.target, false); await(h.io.barrier());
            h.manager.close(); h.io.close();
            assertTrue(calls.get() >= 2 && calls.get() <= 4, "failed storage must not busy retry");
            verify(h.backend, times(1)).close();
            assertEquals(1, h.journal.records(), "unacknowledged SQL must remain durable after terminal close");
            verify(h.plugin, never()).activeGraveSnapshot(); verify(h.plugin, never()).brokenGraveSnapshot();
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void legacySqlListNoOpCannotReplacePendingBrokenContents(boolean brokenList) throws Exception {
        try (var h = new Fixture(4)) {
            var stale = GraveStorageManager.snapshot(h.target); var oldGrave = mock(Grave.class); when(oldGrave.getGravesConfig()).thenReturn(stale);
            h.target.setDestroyed(true); h.target.getItems().get(0).setAmount(7);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var saved = new CountDownLatch(1); var calls = new AtomicInteger();
            doAnswer(call -> {
                assertEquals("prepared:7", SqlJournalHarness.encoded(call.getArgument(0)));
                if (calls.incrementAndGet() == 1) { entered.countDown(); waitFor(release); throw new IllegalStateException("synthetic SQL failure"); }
                saved.countDown(); return null;
            }).when(h.backend).updateExistingGrave(any(), eq(true));
            h.manager.updateExistingGrave(h.target, true); waitFor(entered);
            try { if (brokenList) h.manager.saveBrokenGravesFromObjects(List.of(oldGrave)); else h.manager.saveGravesFromObjects(List.of(oldGrave)); }
            finally { release.countDown(); }
            h.journal.journal.wake(); waitFor(saved);
            verify(h.backend, never()).upsertGrave(any(), anyBoolean()); verify(h.backend, never()).saveGraves(any()); verify(h.backend, never()).saveBrokenGraves(any());
        }
    }
    @Test void saturatedLegacySqlListsCannotCreateRecoveryRows() throws Exception {
        try (var h = new Fixture(1)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var first = h.io.submit(() -> { entered.countDown(); waitFor(release); return true; });
            waitFor(entered); var queued = h.io.submit(() -> true);
            var grave = mock(Grave.class); when(grave.getGravesConfig()).thenReturn(h.target);
            try {
                h.manager.saveGravesFromObjects(List.of(grave));
                h.manager.saveBrokenGravesFromObjects(List.of(grave));
            } finally { release.countDown(); }
            await(first); await(queued); await(h.io.barrier());
            verifyNoInteractions(h.backend); assertEquals(0, h.encodes.get());
        }
    }
}
