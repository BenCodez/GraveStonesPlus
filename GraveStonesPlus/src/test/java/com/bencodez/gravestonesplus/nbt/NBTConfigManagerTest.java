package com.bencodez.gravestonesplus.nbt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NBTConfigManagerTest {
    @TempDir Path root;
    final java.util.concurrent.atomic.AtomicReference<String> lore = new java.util.concurrent.atomic.AtomicReference<>("old lore");
    String rules(boolean keep) {
        String stage = keep ? "keep_nbt_rules" : "grave_nbt_rules";
        return stage + ":\n  safe:\n    path: keep\n    type: EXISTS\n    value: true\n";
    }
    NBTConfigManager manager() throws Exception {
        Files.writeString(root.resolve("nbt_rules.yml"), rules(true));
        GraveStonesPlus plugin = mock(GraveStonesPlus.class);
        var config = mock(com.bencodez.gravestonesplus.config.Config.class);
        when(plugin.getConfigFile()).thenReturn(config); when(config.getKeepItemsWithLore()).thenAnswer(call -> lore.get());
        when(plugin.getDataFolder()).thenReturn(root.toFile()); when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        return new NBTConfigManager(plugin);
    }
    @Test void actualReloadKeepsPreviouslyCapturedGenerationAndPublishesCompleteReplacement() throws Exception {
        var manager = manager(); var old = manager.snapshot();
        assertEquals("safe", old.keep().getFirst().getName()); assertTrue(old.grave().isEmpty());
        lore.set("new lore"); Files.writeString(root.resolve("nbt_rules.yml"), rules(false)); manager.reloadConfig();
        var current = manager.snapshot(); assertTrue(current.keep().isEmpty()); assertEquals("safe", current.grave().getFirst().getName());
        assertEquals("safe", old.keep().getFirst().getName()); assertTrue(old.grave().isEmpty());
        assertEquals("old lore", old.keepLore()); assertEquals("new lore", current.keepLore());
        assertThrows(UnsupportedOperationException.class, () -> old.keep().clear());
    }
    @Test void readersNeverObserveAnUnprotectedGenerationDuringRealReloads() throws Exception {
        var manager = manager(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var writer = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 40; i++) { Files.writeString(root.resolve("nbt_rules.yml"), rules(i % 2 == 0)); manager.reloadConfig(); }
                return null;
            });
            var readers = List.of(pool.submit(() -> verifyGenerations(manager, start)), pool.submit(() -> verifyGenerations(manager, start)));
            start.countDown(); writer.get(10, TimeUnit.SECONDS);
            for (var reader : readers) reader.get(10, TimeUnit.SECONDS);
        }
    }
    Boolean verifyGenerations(NBTConfigManager manager, CountDownLatch start) throws InterruptedException {
        start.await();
        for (int i = 0; i < 20_000; i++) {
            var generation = manager.snapshot();
            assertEquals(1, generation.keep().size() + generation.grave().size());
            assertTrue(generation.ignore().isEmpty());
        }
        return true;
    }
}
