package com.bencodez.gravestonesplus.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.EOFException;
import java.util.List;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Utility to serialize and deserialize a map of inventory slots to {@link ItemStack}s.
 *
 * <p>The array index used when encoding corresponds to the original slot number
 * (nonnegative for the main inventory). Negative slots have distinct appended
 * positions and an optional slot-key suffix. Legacy arrays without that suffix
 * retain their surviving nonnegative keys. Empty slots
 * are represented as {@code null} entries and are omitted when deserializing.</p>
 */
public final class InventorySerializer {

    private static final int SLOT_KEYS = 0x534c5431; // SLT1

    /**
     * Encodes a map of slot indices and items into a Base64 string.
     *
     * @param items map of slot index to {@code ItemStack}; may be empty
     * @return Base64 string representing the items in slot order
     * @throws IOException if writing fails
     */
    public static String encode(HashMap<Integer, ItemStack> items) throws IOException {
        int maxSlot = items.keySet().stream().filter(i -> i >= 0)
                .max(Integer::compareTo).orElse(-1);
        List<Integer> equipment = items.keySet().stream().filter(i -> i < 0).sorted().toList();
        long totalSize = (long) maxSlot + 1 + equipment.size();
        if (totalSize > Integer.MAX_VALUE) {
            throw new IOException("Inventory slot range is too large");
        }
        ItemStack[] array = new ItemStack[(int) totalSize];
        for (Map.Entry<Integer, ItemStack> entry : items.entrySet()) {
            if (entry.getKey() >= 0) array[entry.getKey()] = entry.getValue();
        }
        for (int i = 0; i < equipment.size(); i++) {
            array[maxSlot + 1 + i] = items.get(equipment.get(i));
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeInt(array.length);
            for (ItemStack item : array) {
                oos.writeObject(item);
            }
            if (!equipment.isEmpty()) {
                // Append metadata after the unchanged positional frame. Old readers
                // still retain each value, but cannot restore equipment positions.
                oos.writeInt(SLOT_KEYS);
                oos.writeInt(equipment.size());
                for (int slot : equipment) oos.writeInt(slot);
            }
            oos.flush();
            return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
        }
    }

    /**
     * Decodes a Base64 string back into a map of slot indices and {@code ItemStack}s.
     *
     * @param data Base64-encoded inventory data
     * @return a map of slot index to item; empty if the string was empty
     * @throws IOException if reading fails
     */
    public static HashMap<Integer, ItemStack> decode(String data) throws IOException {
        HashMap<Integer, ItemStack> items = new HashMap<>();
        byte[] bytes = java.util.Base64.getDecoder().decode(data);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream ois = new BukkitObjectInputStream(bais)) {
            // The previous empty writer returned only the valid stream header
            // because its count was not flushed before extracting the bytes.
            if (bytes.length == 4) return items;
            int size = ois.readInt();
            for (int i = 0; i < size; i++) {
                try {
                    ItemStack item = (ItemStack) ois.readObject();
                    if (item != null) {
                        items.put(i, item);
                    }
                } catch (ClassNotFoundException e) {
                    throw new IOException("Could not read inventory data", e);
                }
            }
            int marker;
            try {
                marker = ois.readInt();
            } catch (EOFException legacyEnd) {
                return items;
            }
            if (marker == SLOT_KEYS) {
                int count = ois.readInt();
                if (count <= 0 || count > size) throw new IOException("Invalid slot-key count");
                HashSet<Integer> seen = new HashSet<>();
                for (int i = 0; i < count; i++) {
                    int slot = ois.readInt();
                    if (slot >= 0 || !seen.add(slot)) throw new IOException("Invalid equipment slot key");
                    ItemStack item = items.remove(size - count + i);
                    if (item != null) items.put(slot, item);
                }
            }
        }
        return items;
    }

    // Prevent instantiation
    private InventorySerializer() {
    }
}