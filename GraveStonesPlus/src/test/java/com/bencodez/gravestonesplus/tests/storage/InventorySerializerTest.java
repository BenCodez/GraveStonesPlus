package com.bencodez.gravestonesplus.tests.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.junit.jupiter.api.Test;
import com.bencodez.gravestonesplus.storage.InventorySerializer;

class InventorySerializerTest {
    // Exercise the real Bukkit streams without a server or live player data.
    static final class TestItem extends ItemStack implements Serializable {
        private static final long serialVersionUID = 1L;
        final String label;
        TestItem(String label) { super(Material.STONE); this.label = label; }
        @Override public boolean equals(Object o) { return o instanceof TestItem t && label.equals(t.label); }
        @Override public int hashCode() { return label.hashCode(); }
    }
    private HashMap<Integer, ItemStack> items(int... slots) {
        HashMap<Integer, ItemStack> map = new LinkedHashMap<>();
        for (int slot : slots) map.put(slot, new TestItem("item-" + slot));
        return map;
    }
    private void roundTrip(HashMap<Integer, ItemStack> input) throws Exception {
        var output = InventorySerializer.decode(InventorySerializer.encode(input));
        assertEquals(input.keySet(), output.keySet());
        assertEquals(input, output);
    }
    @Test void mainAndHelmetCollisionRetainsBothItemsInEitherInsertionOrder() throws Exception {
        roundTrip(items(35, -1)); roundTrip(items(-1, 35));
    }
    @Test void allRuntimeSlotsRetainKeysAndDistinctValues() throws Exception {
        HashMap<Integer, ItemStack> input = items(-1, -2, -3, -4, -5);
        for (int i = 0; i < 36; i++) input.put(i, new TestItem("main-" + i));
        roundTrip(input);
    }
    @Test void equipmentOnlyAndSparseMapsRoundTrip() throws Exception {
        for (int slot = -1; slot >= -5; slot--) roundTrip(items(slot));
        roundTrip(items(-1, -2, -3, -4, -5)); roundTrip(items(0, -1));
        roundTrip(items(5, -5)); roundTrip(items()); roundTrip(items(0, 9, 35));
    }
    @Test void positiveSlotsBeyondRuntimeInventoryRemainPositive() throws Exception {
        roundTrip(items(0, 36, 40, 41, -1, -5));
    }
    @Test void everyFormerCollisionPairRetainsBothItems() throws Exception {
        for (int max = 5; max < 36; max++) {
            for (int negative = -1; negative >= -5; negative--) {
                roundTrip(items(max, max + 1 + negative, negative));
            }
        }
    }
    @Test void legacyArraysRetainEverySurvivorWithoutGuessingEquipment() throws Exception {
        for (int size : new int[] {0, 2, 6, 36, 41, 42}) {
            var old = new ByteArrayOutputStream();
            var expected = new HashMap<Integer, ItemStack>();
            try (var out = new BukkitObjectOutputStream(old)) {
                out.writeInt(size);
                for (int i = 0; i < size; i++) {
                    ItemStack item = i % 2 == 0 ? new TestItem("legacy-" + i) : null;
                    out.writeObject(item); if (item != null) expected.put(i, item);
                }
            }
            String encoded = Base64.getEncoder().encodeToString(old.toByteArray());
            assertEquals(expected, InventorySerializer.decode(encoded));
            roundTrip(expected);
        }
    }
    @Test void repeatedCyclesPreserveEquipmentIdentityAndValues() throws Exception {
        var expected = items(0, 31, 32, 33, 34, 35, -1, -2, -3, -4, -5);
        var current = expected;
        for (int i = 0; i < 5; i++) {
            current = InventorySerializer.decode(InventorySerializer.encode(current));
            assertEquals(expected, current);
        }
    }
    @Test void olderReaderStillSeesEveryNewValueOnce() throws Exception {
        var input = items(35, 40, -1, -2, -3, -4, -5);
        byte[] bytes = Base64.getDecoder().decode(InventorySerializer.encode(input));
        var values = new ArrayList<ItemStack>();
        try (var in = new org.bukkit.util.io.BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            int size = in.readInt();
            for (int slot = 0; slot < size; slot++) {
                ItemStack item = (ItemStack) in.readObject();
                if (item != null) values.add(item);
            }
        }
        assertEquals(input.size(), values.size());
        assertEquals(new HashSet<>(input.values()), new HashSet<>(values));
    }
    @Test void historicalUnflushedEmptyEncodingRemainsReadable() throws Exception {
        assertTrue(InventorySerializer.decode("rO0ABQ==").isEmpty());
    }
    @Test void malformedRecognizedSuffixFailsInsteadOfReturningWrongSlots() throws Exception {
        for (int[] keys : new int[][] {{1}, {-1, -1}}) {
            var bytes = new ByteArrayOutputStream();
            try (var out = new BukkitObjectOutputStream(bytes)) {
                out.writeInt(2); out.writeObject(new TestItem("one")); out.writeObject(new TestItem("two"));
                out.writeInt(0x534c5431); out.writeInt(keys.length);
                for (int key : keys) out.writeInt(key);
            }
            String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
            assertThrows(IOException.class, () -> InventorySerializer.decode(encoded));
        }
    }

}
