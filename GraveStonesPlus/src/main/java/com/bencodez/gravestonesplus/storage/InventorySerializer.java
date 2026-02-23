package com.bencodez.gravestonesplus.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

/**
 * Utility to serialize and deserialize a map of inventory slots to {@link ItemStack}s.
 *
 * <p>The array index used when encoding corresponds to the original slot number
 * (0–35 for the main inventory and negative values for armour/off?hand).  Empty slots
 * are represented as {@code null} entries and are omitted when deserializing.</p>
 */
public final class InventorySerializer {

    /**
     * Encodes a map of slot indices and items into a Base64 string.
     *
     * @param items map of slot index to {@code ItemStack}; may be empty
     * @return Base64 string representing the items in slot order
     * @throws IOException if writing fails
     */
    public static String encode(HashMap<Integer, ItemStack> items) throws IOException {
        // Determine the highest positive slot index
        int maxSlot = items.keySet().stream()
                .filter(i -> i >= 0)
                .max(Integer::compareTo)
                .orElse(-1);
        // Determine the number of armour/off?hand entries
        int minSlot = items.keySet().stream()
                .filter(i -> i < 0)
                .min(Integer::compareTo)
                .orElse(0);
        int totalSize = Math.max(maxSlot + 1, Math.abs(minSlot) + 1);

        ItemStack[] array = new ItemStack[totalSize];
        for (Map.Entry<Integer, ItemStack> e : items.entrySet()) {
            int index = e.getKey();
            // Normal inventory slots use 0+, negative values are remapped to the end
            if (index >= 0) {
                array[index] = e.getValue();
            } else {
                array[array.length + index] = e.getValue();
            }
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BukkitObjectOutputStream oos = new BukkitObjectOutputStream(baos)) {
            oos.writeInt(array.length);
            for (ItemStack item : array) {
                oos.writeObject(item);
            }
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
        }
        return items;
    }

    // Prevent instantiation
    private InventorySerializer() {
    }
}