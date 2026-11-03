package com.bencodez.gravestonesplus.storage;

import java.util.*;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.inventory.ItemStack;

/** Owner-thread capture of the existing Bukkit YAML item representation. */
final class PreparedLegacyItems {
    private PreparedLegacyItems() { }

    static Map<Integer, Object> capture(Map<Integer, ItemStack> items) {
        Map<Integer, Object> captured = new LinkedHashMap<>();
        Set<Object> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        items.forEach((slot, item) -> captured.put(slot, detach(item, visiting, 0)));
        return Collections.unmodifiableMap(captured);
    }

    private static Object detach(Object value, Set<Object> visiting, int depth) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal) return value;
        if (depth > 64 || !visiting.add(value)) throw new IllegalArgumentException("Cyclic or excessively nested legacy item data");
        try {
            if (value instanceof ConfigurationSerializable serializable) {
                Map<String, Object> encoded = new LinkedHashMap<>();
                encoded.put(ConfigurationSerialization.SERIALIZED_TYPE_KEY, ConfigurationSerialization.getAlias(serializable.getClass()));
                // Match Bukkit's representer: serialized fields retain their original precedence.
                encoded.putAll(serializable.serialize());
                return detach(encoded, visiting, depth + 1);
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> result = new LinkedHashMap<>();
                map.forEach((key, item) -> result.put(detach(key, visiting, depth + 1), detach(item, visiting, depth + 1)));
                return Collections.unmodifiableMap(result);
            }
            if (value instanceof Collection<?> collection) {
                List<Object> result = new ArrayList<>(collection.size());
                for (Object item : collection) result.add(detach(item, visiting, depth + 1));
                return Collections.unmodifiableList(result);
            }
            // SnakeYAML represents byte arrays as binary scalars; other arrays as sequences.
            if (value instanceof byte[] bytes) return bytes.clone();
            if (value instanceof Date date) return new Date(date.getTime());
            if (value.getClass().isArray()) {
                int length = java.lang.reflect.Array.getLength(value); List<Object> result = new ArrayList<>(length);
                for (int i = 0; i < length; i++) result.add(detach(java.lang.reflect.Array.get(value, i), visiting, depth + 1));
                return Collections.unmodifiableList(result);
            }
            throw new IllegalArgumentException("Unsupported legacy item data: " + value.getClass().getName());
        } finally { visiting.remove(value); }
    }
}
