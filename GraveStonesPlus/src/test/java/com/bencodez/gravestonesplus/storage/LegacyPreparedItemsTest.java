package com.bencodez.gravestonesplus.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.configuration.serialization.*;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.gravestonesplus.graves.FeatureIO;

class LegacyPreparedItemsTest {
    @TempDir Path directory;

    @SerializableAs("GspTestLegacyMeta")
    public static final class LegacyMeta implements ConfigurationSerializable {
        final Thread owner;
        final List<String> lore;
        final byte[] data;
        LegacyMeta(List<String> lore, byte[] data) { owner = Thread.currentThread(); this.lore = lore; this.data = data; }
        public Map<String, Object> serialize() {
            assertSame(owner, Thread.currentThread(), "metadata serialized off owner");
            return Map.of("lore", lore, "custom-data", data);
        }
        public static LegacyMeta deserialize(Map<String, Object> fields) {
            @SuppressWarnings("unchecked") List<String> lore = (List<String>) fields.get("lore");
            return new LegacyMeta(lore, (byte[]) fields.get("custom-data"));
        }
    }
    @SerializableAs("GspTestLegacyItem")
    public static final class LegacyItem extends ItemStack {
        final Thread owner;
        final LegacyMeta custom;
        LegacyItem(int amount, LegacyMeta custom) { super(Material.STONE, amount); owner = Thread.currentThread(); this.custom = custom; }
        @Override public Map<String, Object> serialize() {
            assertSame(owner, Thread.currentThread(), "item serialized off owner");
            return Map.of("type", "STONE", "amount", getAmount(), "meta", custom);
        }
        @Override public LegacyItem clone() { return new LegacyItem(getAmount(), custom); }
        public static LegacyItem deserialize(Map<String, Object> fields) {
            return new LegacyItem(((Number) fields.get("amount")).intValue(), (LegacyMeta) fields.get("meta"));
        }
    }
    private GravesConfig source(LegacyMeta meta) {
        var items = new HashMap<Integer, ItemStack>();
        items.put(0, new LegacyItem(2, meta)); items.put(-5, new LegacyItem(1, meta));
        var record = new GravesConfig(UUID.randomUUID(), "owner", new Location(null, 1, 64, 2), items,
                17, "death", 4, false, 0, null, null, "PLAYER_HEAD");
        record.setWorldId(UUID.randomUUID()); return record;
    }
    @Test void flatFileAndStartupFallbackRoundTripDetachedItemsAndNestedMetadata() throws Exception {
        ConfigurationSerialization.registerClass(LegacyMeta.class);
        ConfigurationSerialization.registerClass(LegacyItem.class);
        ConfigurationSerialization.registerClass(GravesConfig.class);
        try (var encoding = mockStatic(InventorySerializer.class); var bukkit = mockStatic(Bukkit.class); var io = new FeatureIO()) {
            encoding.when(() -> InventorySerializer.encode(any())).thenThrow(new IOException("binary unavailable"));
            var meta = new LegacyMeta(new ArrayList<>(List.of("custom lore")), new byte[]{1, 2, 3});
            var source = source(meta); var captured = GraveStorageManager.snapshot(source);
            source.getItems().get(0).setAmount(9); meta.lore.add("later mutation"); meta.data[0] = 9;
            assertThrows(java.io.UncheckedIOException.class, captured::encodedItemsForStorage, "SQL must not silently accept legacy YAML");
            var locations = mock(GraveLocations.class);
            var yaml = new YamlConfiguration(); when(locations.getData()).thenReturn(yaml);
            Path path = directory.resolve("Graves.yml"); when(locations.getdFile()).thenReturn(path.toFile());
            doCallRealMethod().when(locations).saveSnapshot(any(), any());
            var backend = new FlatFileGraveStorageBackend(locations);
            io.submit(() -> { backend.saveSnapshot(List.of(captured), List.of()); return true; }).get(2, TimeUnit.SECONDS);
            assertRestored(path, source);
            // Interrupted-startup merge uses only plain prepared values, and retains unrelated records/settings.
            Files.writeString(path, Files.readString(path) + "owner-extension: retained\n");
            io.submit(() -> { backend.saveStartupSnapshot(List.of(captured), List.of()); return true; }).get(2, TimeUnit.SECONDS);
            assertRestored(path, source);
            assertTrue(Files.readString(path).contains("owner-extension: retained"));
            encoding.verify(() -> InventorySerializer.encode(any()), times(1));
        } finally {
            ConfigurationSerialization.unregisterClass(LegacyMeta.class);
            ConfigurationSerialization.unregisterClass(LegacyItem.class);
            ConfigurationSerialization.unregisterClass(GravesConfig.class);
        }
    }
    private void assertRestored(Path path, GravesConfig source) throws Exception {
        var read = new YamlConfiguration(); read.loadFromString(Files.readString(path));
        var records = read.getList("graves"); assertNotNull(records); assertEquals(1, records.size());
        var restored = assertInstanceOf(GravesConfig.class, records.get(0));
        assertEquals(source.getUuid(), restored.getUuid()); assertEquals(source.getWorldId(), restored.getWorldId());
        assertEquals(17, restored.getExp()); assertEquals(Set.of(0, -5), restored.getItems().keySet());
        var item = assertInstanceOf(LegacyItem.class, restored.getItems().get(0));
        assertEquals(2, item.getAmount()); assertEquals(List.of("custom lore"), item.custom.lore);
        assertArrayEquals(new byte[]{1, 2, 3}, item.custom.data);
        assertEquals(1, restored.getItems().get(-5).getAmount());
    }
    @Test void cyclicLegacyMetadataFailsExplicitlyWithoutDiscardingInventory() throws Exception {
        try (var encoding = mockStatic(InventorySerializer.class)) {
            encoding.when(() -> InventorySerializer.encode(any())).thenThrow(new IOException("binary unavailable"));
            var item = mock(ItemStack.class); when(item.clone()).thenReturn(item);
            Map<String, Object> cycle = new HashMap<>(); cycle.put("self", cycle);
            when(item.serialize()).thenReturn(cycle);
            var source = source(new LegacyMeta(List.of(), new byte[0])); source.getItems().put(0, item);
            var captured = GraveStorageManager.snapshot(source);
            var failure = assertThrows(java.io.UncheckedIOException.class, captured::preparedRecordForStorage);
            assertInstanceOf(IllegalArgumentException.class, failure.getSuppressed()[0]);
            assertEquals(Set.of(0, -5), captured.getItems().keySet());
        }
    }
}
