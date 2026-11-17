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
        final org.mockito.MockedStatic<InventorySerializer> encoding = mockStatic(InventorySerializer.class);
        final AtomicInteger encodes = new AtomicInteger();
        final GravesConfig target = record(1);
        Fixture(int capacity) throws Exception {
            io = new FeatureIO(capacity);
            encoding.when(() -> InventorySerializer.encode(any())).thenAnswer(call -> {
                assertSame(owner, Thread.currentThread(), "encoding must stay on the caller owner");
                encodes.incrementAndGet(); return "prepared";
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
        @Override public void close() { manager.close(); io.close(); encoding.close(); }
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
            assertEquals(1, snapshots.size()); assertEquals(2, snapshots.getFirst().getItems().get(0).getAmount());
            assertEquals(1, h.encodes.get(), "normal SQL write encoded more than its one input");
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
            for (var record : records) {
                h.manager.restorationResolved(record);
                h.manager.updateExistingGrave(record, true);
            }
            await(h.io.barrier());
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
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var writes = new CopyOnWriteArrayList<String>();
            var calls = new AtomicInteger();
            doAnswer(call -> {
                var captured = (GravesConfig) call.getArgument(0);
                writes.add(captured.getItems().get(0).getAmount() + ":" + call.getArgument(1));
                if (calls.incrementAndGet() == 1) {
                    entered.countDown(); waitFor(release); throw new IllegalStateException("synthetic SQL failure");
                }
                assertNotSame(h.owner, Thread.currentThread()); return null;
            }).when(h.backend).upsertGrave(any(), anyBoolean());
            h.manager.upsertGrave(h.target, false); waitFor(entered);
            try {
                h.target.setDestroyed(true); h.target.getItems().get(0).setAmount(7);
                h.operation(later); h.target.getItems().get(0).setAmount(99);
            } finally { release.countDown(); }
            await(h.io.barrier());
            assertEquals(List.of("2:false", "7:true"), writes);
            assertEquals(2, h.encodes.get());
            verify(h.plugin, never()).activeGraveSnapshot(); verify(h.plugin, never()).brokenGraveSnapshot();
        }
    }
    @Test void newOwnerTransitionDuringRecoverySurvivesOlderAcknowledgement() throws Exception {
        try (var h = new Fixture(4)) {
            var retryEntered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var calls = new AtomicInteger(); var writes = new CopyOnWriteArrayList<String>();
            doAnswer(call -> {
                var captured = (GravesConfig) call.getArgument(0);
                writes.add(captured.getItems().get(0).getAmount() + ":" + call.getArgument(1));
                int number = calls.incrementAndGet();
                if (number == 1) throw new IllegalStateException("synthetic SQL failure");
                if (number == 2) { retryEntered.countDown(); waitFor(release); }
                assertNotSame(h.owner, Thread.currentThread()); return null;
            }).when(h.backend).upsertGrave(any(), anyBoolean());
            h.manager.upsertGrave(h.target, false); waitFor(retryEntered);
            try {
                h.target.setDestroyed(true); h.target.getItems().get(0).setAmount(7);
                h.manager.updateExistingGrave(h.target, true);
                h.target.getItems().get(0).setAmount(99);
            } finally { release.countDown(); }
            await(h.io.barrier());
            assertEquals(List.of("2:false", "2:false", "7:true"), writes);
            verify(h.backend, never()).updateExistingGrave(any(), anyBoolean());
            assertEquals(2, h.encodes.get());
        }
    }
    @Test void saturatedAdmissionRecoversOnlyPreparedMutationBeforeTerminalClose() throws Exception {
        try (var h = new Fixture(1)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var first = h.io.submit(() -> { entered.countDown(); waitFor(release); return true; });
            waitFor(entered);
            var queued = h.io.submit(() -> true);
            var saved = new CopyOnWriteArrayList<Integer>();
            doAnswer(call -> {
                assertNotSame(h.owner, Thread.currentThread());
                saved.add(((GravesConfig) call.getArgument(0)).getItems().get(0).getAmount()); return null;
            }).when(h.backend).upsertGrave(any(), anyBoolean());
            try {
                h.manager.upsertGrave(h.target, false); h.target.getItems().get(0).setAmount(99);
                h.manager.close(); h.manager.close();
            } finally { release.countDown(); }
            await(first); await(queued); h.io.close();
            assertEquals(List.of(2), saved); verify(h.backend, times(1)).close();
            var order = inOrder(h.backend); order.verify(h.backend).upsertGrave(any(), eq(false)); order.verify(h.backend).close();
            verify(h.plugin, never()).activeGraveSnapshot(); verify(h.plugin, never()).brokenGraveSnapshot();
        }
    }
    @Test void permanentFailureRetainsPreparedContentsAndClosesOnceWithoutBusyRetry() throws Exception {
        try (var h = new Fixture(4)) {
            var calls = new AtomicInteger();
            doAnswer(call -> {
                assertNotSame(h.owner, Thread.currentThread()); calls.incrementAndGet();
                assertEquals(2, ((GravesConfig) call.getArgument(0)).getItems().get(0).getAmount());
                throw new AssertionError("synthetic permanent backend failure");
            }).when(h.backend).upsertGrave(any(), anyBoolean());
            h.manager.upsertGrave(h.target, false); await(h.io.barrier());
            h.manager.close(); h.io.close();
            assertTrue(calls.get() >= 2 && calls.get() <= 4, "failed storage must not busy retry");
            verify(h.backend, times(1)).close();
            var retained = GraveStorageManager.class.getDeclaredField("retained"); retained.setAccessible(true);
            assertNotNull(retained.get(h.manager), "unflushed recovery must remain visible after terminal close");
            verify(h.plugin, never()).activeGraveSnapshot(); verify(h.plugin, never()).brokenGraveSnapshot();
        }
    }
}
