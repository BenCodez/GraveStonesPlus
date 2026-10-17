package com.bencodez.gravestonesplus.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class StartupGraveDocumentTest {
    private Map<String, Object> record(long time, String items) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("==", GravesConfig.class.getName());
        record.put("UUID", "4c8293df-0d49-4d9b-895d-ea405b98b929");
        record.put("Time", time);
        record.put("World", "67eaf30b-0bcf-4392-9352-0e703a3d9d56");
        record.put("X", -12); record.put("Y", 70); record.put("Z", 9);
        record.put("ItemsEncoded", items); record.put("EXP", 17);
        return record;
    }

    private Object yamlValue(Object value) { return new Yaml().load(new Yaml().dump(value)); }

    @Test void preservesExistingAndAddsNewCaptureWithoutDeserializingBukkit() {
        Map<String, Object> original = record(1, "old contents");
        original.put("Items", Map.of("0", Map.of("==", "org.bukkit.inventory.ItemStack", "type", "DIAMOND")));
        String document = new Yaml().dump(Map.of("graves", List.of(original), "brokengraves", List.of(),
                "owner-extension", "retain this"));
        String merged = StartupGraveDocument.merge(document, List.of(record(2, "new contents")), List.of());
        Map<?, ?> loaded = new Yaml().load(merged);
        assertEquals("retain this", loaded.get("owner-extension"));
        assertEquals(yamlValue(List.of(original, record(2, "new contents"))), loaded.get("graves"));
    }

    @Test void movesSameIdentityBetweenSectionsWithoutDuplicatingContents() {
        String document = new Yaml().dump(Map.of("graves", List.of(record(1, "old")),
                "brokengraves", List.of(record(2, "other"))));
        Map<?, ?> merged = new Yaml().load(StartupGraveDocument.merge(document,
                List.of(), List.of(record(1, "updated"))));
        assertEquals(List.of(), merged.get("graves"));
        assertEquals(yamlValue(List.of(record(2, "other"), record(1, "updated"))), merged.get("brokengraves"));
    }

    @Test void repeatedMergeDoesNotDuplicateCapturedGrave() {
        String once = StartupGraveDocument.merge("", List.of(record(1, "items")), List.of());
        Map<?, ?> twice = new Yaml().load(StartupGraveDocument.merge(once, List.of(record(1, "items")), List.of()));
        assertEquals(yamlValue(List.of(record(1, "items"))), twice.get("graves"));
    }

    @Test void rejectsAmbiguousPreparedStateAndInvalidDocument() {
        assertThrows(IllegalArgumentException.class, () -> StartupGraveDocument.merge("",
                List.of(record(1, "items")), List.of(record(1, "items"))));
        assertThrows(IllegalArgumentException.class, () -> StartupGraveDocument.merge("graves: invalid",
                List.of(record(1, "items")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> StartupGraveDocument.merge("graves: []\ngraves: []",
                List.of(record(1, "items")), List.of()));
    }

    @Test void preservesUnknownTaggedLegacyRecordsWithoutConstructingTheirClasses() {
        String merged = StartupGraveDocument.merge("graves:\n- !unavailable.LegacyRecord {payload: retained}\n",
                List.of(record(1, "items")), List.of());
        assertTrue(merged.contains("!unavailable.LegacyRecord"));
        assertTrue(merged.contains("retained"));
    }
}
