package com.bencodez.gravestonesplus.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.FeatureIO;
import com.bencodez.gravestonesplus.graves.Grave;

class PreparedStorageSnapshotTest {
    private static GravesConfig source() {
        var items = new HashMap<Integer, ItemStack>();
        items.put(0, new ItemStack(Material.STONE, 2));
        var grave = new GravesConfig(UUID.randomUUID(), "owner", new Location(null, 1, 64, 2),
                items, 3, "death", 4, false, 0, null, null, "PLAYER_HEAD");
        grave.setWorldId(UUID.randomUUID()); return grave;
    }
    @Test void preparedSnapshotUsesCapturedEncodingOnAnotherThread() throws Exception {
        var source = source();
        try (var encoding = mockStatic(InventorySerializer.class)) {
            encoding.when(() -> InventorySerializer.encode(any())).thenReturn("captured");
            var prepared = GraveStorageManager.snapshot(source);
            source.getItems().get(0).setAmount(7);
            assertEquals(2, prepared.getItems().get(0).getAmount());
            try (var io = new FeatureIO()) {
                assertEquals("captured", io.submit(() -> prepared.serialize().get("ItemsEncoded")).get(2, TimeUnit.SECONDS));
                assertEquals("captured", io.submit(prepared::encodedItemsForStorage).get(2, TimeUnit.SECONDS));
            }
            encoding.verify(() -> InventorySerializer.encode(any()), times(1));
        }
    }
    @Test void failedWorkerNeverRescansLiveItemsForRecovery() throws Exception {
        var plugin = mock(GraveStonesPlus.class);
        try (var io = new FeatureIO(); var encoding = mockStatic(InventorySerializer.class)) {
            encoding.when(() -> InventorySerializer.encode(any())).thenReturn("captured");
            when(plugin.getFeatureIO()).thenReturn(io);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            var grave = mock(Grave.class); when(grave.getGravesConfig()).thenReturn(source());
            var owner = Thread.currentThread();
            when(plugin.activeGraveSnapshot()).thenAnswer(call -> {
                assertSame(owner, Thread.currentThread(), "live grave rescan on I/O worker"); return List.of(grave);
            });
            when(plugin.brokenGraveSnapshot()).thenReturn(List.of());
            var backend = mock(GraveStorageBackend.class);
            doThrow(new IllegalStateException("fixture storage failure")).when(backend).saveSnapshot(any(), any());
            var manager = new GraveStorageManager(plugin);
            var field = GraveStorageManager.class.getDeclaredField("backend"); field.setAccessible(true); field.set(manager, backend);
            var result = manager.saveSnapshotFromObjects(List.of(grave), List.of());
            assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            var retained = GraveStorageManager.class.getDeclaredField("retained"); retained.setAccessible(true);
            assertNotNull(retained.get(manager));
            verify(plugin, times(1)).activeGraveSnapshot();
        }
    }
    @Test void olderFailedWriteRetainsTheNewerRemovalSnapshot() throws Exception {
        var plugin = mock(GraveStonesPlus.class);
        try (var io = new FeatureIO(); var encoding = mockStatic(InventorySerializer.class)) {
            encoding.when(() -> InventorySerializer.encode(any())).thenReturn("captured");
            when(plugin.getFeatureIO()).thenReturn(io);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            var grave = mock(Grave.class); when(grave.getGravesConfig()).thenReturn(source());
            var current = new java.util.concurrent.atomic.AtomicReference<List<Grave>>(List.of(grave));
            when(plugin.activeGraveSnapshot()).thenAnswer(call -> current.get());
            when(plugin.brokenGraveSnapshot()).thenReturn(List.of());
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var writes = new java.util.concurrent.atomic.AtomicInteger();
            var backend = mock(GraveStorageBackend.class);
            doAnswer(call -> {
                if (writes.getAndIncrement() == 0) {
                    entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS));
                    throw new IllegalStateException("older write failed");
                }
                List<GravesConfig> records = call.getArgument(0);
                assertTrue(records.isEmpty(), "recovery resurrected the removed grave"); return null;
            }).when(backend).saveSnapshot(any(), any());
            var manager = new GraveStorageManager(plugin);
            var field = GraveStorageManager.class.getDeclaredField("backend"); field.setAccessible(true); field.set(manager, backend);
            var old = manager.saveSnapshotFromObjects(List.of(grave), List.of());
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS)); current.set(List.of());
                var removal = manager.saveSnapshotFromObjects(List.of(), List.of());
                release.countDown();
                assertThrows(ExecutionException.class, () -> old.get(2, TimeUnit.SECONDS));
                assertTrue(removal.get(2, TimeUnit.SECONDS));
                assertTrue(writes.get() >= 3, "retained latest state was not flushed");
            } finally { release.countDown(); }
        }
    }
    @Test void failureOfBothBinaryAndLegacyEncodingRemainsRetainedThroughWorkerRecovery() throws Exception {
        var plugin = mock(GraveStonesPlus.class);
        try (var io = new FeatureIO(); var encoding = mockStatic(InventorySerializer.class)) {
            encoding.when(() -> InventorySerializer.encode(any())).thenThrow(new java.io.IOException("fixture"));
            when(plugin.getFeatureIO()).thenReturn(io);
            when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
            var unsupported = source();
            var item = mock(ItemStack.class); when(item.clone()).thenReturn(item);
            when(item.serialize()).thenReturn(Map.of("unsupported", new Object()));
            unsupported.getItems().put(0, item);
            var grave = mock(Grave.class); when(grave.getGravesConfig()).thenReturn(unsupported);
            when(plugin.activeGraveSnapshot()).thenReturn(List.of(grave));
            when(plugin.brokenGraveSnapshot()).thenReturn(List.of());
            var backend = mock(GraveStorageBackend.class);
            doAnswer(call -> {
                List<GravesConfig> records = call.getArgument(0);
                for (GravesConfig record : records) record.serialize(); return null;
            }).when(backend).saveSnapshot(any(), any());
            var manager = new GraveStorageManager(plugin);
            var field = GraveStorageManager.class.getDeclaredField("backend"); field.setAccessible(true); field.set(manager, backend);
            var result = manager.saveSnapshotFromObjects(List.of(grave), List.of());
            var failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(java.io.UncheckedIOException.class, failure.getCause());
            var retained = GraveStorageManager.class.getDeclaredField("retained"); retained.setAccessible(true);
            assertNotNull(retained.get(manager));
            encoding.verify(() -> InventorySerializer.encode(any()), times(2));
        }
    }
}
