package com.bencodez.gravestonesplus.tests;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import com.bencodez.gravestonesplus.storage.GravesConfig;

class LegacyGraveRecordTest {
    @Test
    void legacyMapRetainsSlotKeysContentsExperienceAndDisplayIdentity() {
        UUID worldId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID display = UUID.randomUUID();
        ItemStack helmet = mock(ItemStack.class);
        ItemStack main = mock(ItemStack.class);
        Map<String, ItemStack> legacyItems = new TreeMap<>();
        legacyItems.put("-1", helmet);
        legacyItems.put("27", main);
        Map<String, Object> old = new HashMap<>();
        old.put("UUID", owner.toString());
        old.put("PlayerName", "Owner");
        old.put("World", worldId.toString());
        old.put("X", 12);
        old.put("Y", 64);
        old.put("Z", -8);
        old.put("EXP", 42);
        old.put("Time", 1234L);
        old.put("DisplayUUID", display.toString());
        old.put("Items", legacyItems);
        World world = mock(World.class);
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld(worldId)).thenReturn(world);
            GravesConfig record = GravesConfig.deserialize(old);
            assertEquals(owner, record.getUuid());
            assertSame(world, record.getLocation().getWorld());
            assertEquals(42, record.getExp());
            assertEquals(1234L, record.getTime());
            assertEquals(display, record.getDisplayUUID());
            assertEquals("DISPLAY_ENTITY", record.getGraveDisplayType());
            assertEquals(2, record.getItems().size());
            assertSame(helmet, record.getItems().get(-1));
            assertSame(main, record.getItems().get(27));
            assertFalse(record.isDestroyed());
        }
    }
}
