package com.bencodez.gravestonesplus.tests;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;

import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.config.Config;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GraveDisplayType;
import com.bencodez.gravestonesplus.listeners.GraveBlockProtectionListener;
import com.bencodez.gravestonesplus.storage.GraveStorageManager;
import com.bencodez.gravestonesplus.storage.GravesConfig;

class GraveRecoveryTest {
    private GravesConfig record(boolean broken, String displayType, Block block) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        Location location = mock(Location.class);
        when(location.getWorld()).thenReturn(world);
        when(location.getBlock()).thenReturn(block);
        HashMap<Integer, ItemStack> items = new HashMap<>();
        items.put(-1, mock(ItemStack.class));
        items.put(27, mock(ItemStack.class));
        return new GravesConfig(UUID.randomUUID(), "Owner", location,
                items, 42, "Death", 1234L, broken, broken ? 4567L : 0,
                null, null, displayType);
    }

    @Test
    void airAndFluidsRepairActiveRecordWithoutMovingContents() {
        for (Material material : new Material[] {Material.AIR, Material.CAVE_AIR, Material.WATER, Material.LAVA}) {
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(material);
            GravesConfig record = record(false, "PLAYER_HEAD", block);
            GraveStonesPlus plugin = mock(GraveStonesPlus.class, RETURNS_DEEP_STUBS);
            Grave grave = new Grave(plugin, record);
            var items = record.getItems();
            assertTrue(grave.recoverMissingMarker(), material.name());
            verify(plugin.getBukkitScheduler()).runTask(eq(plugin), any(Runnable.class), eq(record.getLocation()));
            assertSame(record, grave.getGravesConfig());
            assertSame(items, record.getItems());
            assertEquals(42, record.getExp());
            assertFalse(record.isDestroyed());
        }
    }

    @Test
    void existingMarkersAreAcceptedAndSolidReplacementsAreNotOverwritten() {
        for (String type : new String[] {"PLAYER_HEAD", "CHEST", "DISPLAY_ENTITY"}) {
            Block block = mock(Block.class);
            when(block.getType()).thenReturn(Material.valueOf(type.equals("DISPLAY_ENTITY") ? "BARRIER" : type));
            Grave grave = spy(new Grave(null, record(false, type, block)));
            assertTrue(grave.recoverMissingMarker());
            verify(grave, never()).createGrave();
        }
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.STONE);
        Grave grave = spy(new Grave(null, record(false, "PLAYER_HEAD", block)));
        assertFalse(grave.recoverMissingMarker());
        verify(grave, never()).createGrave();
    }

    @Test
    void brokenRecordsNeverAutomaticallyResurrect() {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.WATER);
        Grave grave = spy(new Grave(null, record(true, "PLAYER_HEAD", block)));
        assertTrue(grave.isRemove());
        assertFalse(grave.recoverMissingMarker());
        verify(grave, never()).createGrave();
    }

    @Test
    void explicitRecreationClearsRemovalAndDestroyedState() {
        GraveStonesPlus plugin = mock(GraveStonesPlus.class, RETURNS_DEEP_STUBS);
        GravesConfig record = record(true, "CHEST", mock(Block.class));
        Grave grave = spy(new Grave(plugin, record));
        grave.setLastClick(987L);
        doNothing().when(grave).createGrave();
        grave.createBrokenGrave();
        assertFalse(grave.isRemove());
        assertFalse(record.isDestroyed());
        assertEquals(0, record.getDestroyedTime());
        assertEquals(0, grave.getLastClick());
        assertEquals(42, record.getExp());
        verify(grave).createGrave();
    }

    @Test
    void staleRecoveryClickDoesNotRecreateOrDuplicateAnActiveGrave() throws Exception {
        GraveStonesPlus plugin = mock(GraveStonesPlus.class, RETURNS_DEEP_STUBS);
        GraveStorageManager storage = mock(GraveStorageManager.class);
        GravesConfig record = record(true, "CHEST", mock(Block.class));
        Grave grave = spy(new Grave(plugin, record));
        doNothing().when(grave).createGrave();
        ArrayList<Grave> active = new ArrayList<>();
        ArrayList<Grave> broken = new ArrayList<>();
        broken.add(grave);
        setField(plugin, "graves", active);
        setField(plugin, "brokenGraves", broken);
        setField(plugin, "storageManager", storage);
        doCallRealMethod().when(plugin).recreateBrokenGrave(grave);
        plugin.recreateBrokenGrave(grave);
        plugin.recreateBrokenGrave(grave);
        assertEquals(1, active.size());
        assertSame(grave, active.getFirst());
        assertTrue(broken.isEmpty());
        assertFalse(grave.isRemove());
        verify(grave, times(1)).createBrokenGrave();
        verify(storage, times(1)).saveGravesFromObjects(active);
        verify(storage, times(1)).saveBrokenGravesFromObjects(broken);
    }

    @Test
    void chestModeMenuIconNeverLooksUpAPlayerProfile() throws Exception {
        GraveStonesPlus plugin = mock(GraveStonesPlus.class);
        Config config = mock(Config.class);
        when(plugin.getConfigFile()).thenReturn(config);
        when(config.getGraveDisplayTypeEnum()).thenReturn(GraveDisplayType.CHEST);
        Grave grave = new Grave(plugin, record(true, "PLAYER_HEAD", mock(Block.class)));
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
                MockedConstruction<ItemBuilder> builders = mockConstruction(ItemBuilder.class, (mock, context) -> {
                    assertEquals(Material.CHEST, context.arguments().getFirst());
                })) {
            var method = Grave.class.getDeclaredMethod("getGUIIcon");
            method.setAccessible(true);
            Object icon = method.invoke(grave);
            assertSame(builders.constructed().getFirst(), icon);
            bukkit.verifyNoInteractions();
        }
    }

    @Test
    void deferredPhysicsRepairDoesNotRestoreRemovedGrave() {
        GraveStonesPlus plugin = mock(GraveStonesPlus.class, RETURNS_DEEP_STUBS);
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.PLAYER_HEAD);
        GravesConfig record = record(false, "PLAYER_HEAD", block);
        when(block.getLocation()).thenReturn(record.getLocation());
        Grave grave = spy(new Grave(plugin, record));
        BlockPhysicsEvent event = mock(BlockPhysicsEvent.class);
        when(event.getBlock()).thenReturn(block);
        MiscUtils misc = mock(MiscUtils.class);
        try (MockedStatic<MiscUtils> utils = mockStatic(MiscUtils.class)) {
            utils.when(MiscUtils::getInstance).thenReturn(misc);
            when(misc.getBlockMeta(block, "Grave")).thenReturn(grave);
            new GraveBlockProtectionListener(plugin).onPhysics(event);
            verify(event).setCancelled(true);
            ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
            verify(plugin.getBukkitScheduler()).runTask(eq(plugin), callback.capture(), eq(record.getLocation()));
            record.setDestroyed(true);
            when(block.getType()).thenReturn(Material.AIR);
            callback.getValue().run();
            verify(grave, never()).createGrave();
        }
    }

    @Test
    void queuedMarkerCreationDoesNotRestoreRemovedGrave() {
        GraveStonesPlus plugin = mock(GraveStonesPlus.class, RETURNS_DEEP_STUBS);
        Block block = mock(Block.class);
        GravesConfig record = record(false, "CHEST", block);
        Grave grave = new Grave(plugin, record);
        grave.createGrave();
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(plugin.getBukkitScheduler()).runTask(eq(plugin), callback.capture(), eq(record.getLocation()));
        record.setDestroyed(true);
        callback.getValue().run();
        verifyNoInteractions(block);
    }

    @Test
    void recoveryRechecksSolidReplacementAtFinalWrite() {
        GraveStonesPlus plugin = mock(GraveStonesPlus.class, RETURNS_DEEP_STUBS);
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.WATER);
        GravesConfig record = record(false, "CHEST", block);
        Grave grave = new Grave(plugin, record);
        assertTrue(grave.recoverMissingMarker());
        ArgumentCaptor<Runnable> callback = ArgumentCaptor.forClass(Runnable.class);
        verify(plugin.getBukkitScheduler()).runTask(eq(plugin), callback.capture(), eq(record.getLocation()));
        when(block.getType()).thenReturn(Material.STONE);
        callback.getValue().run();
        verify(block, never()).setType(any(Material.class), anyBoolean());
        assertFalse(record.isDestroyed());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = GraveStonesPlus.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

}
