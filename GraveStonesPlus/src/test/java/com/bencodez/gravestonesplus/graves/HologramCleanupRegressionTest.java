package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;
import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.hologram.Hologram;
import com.bencodez.advancedcore.api.hologram.HologramHandler;
import com.bencodez.gravestonesplus.events.GraveRemoveEvent;
import com.bencodez.gravestonesplus.events.GraveRemoveReason;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.junit.jupiter.api.Test;

class HologramCleanupRegressionTest {
    private static void field(Object target, Class<?> type, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    @SuppressWarnings("unchecked")
    private static List<Hologram> registered(HologramHandler handler) throws Exception {
        Field field = HologramHandler.class.getDeclaredField("list"); field.setAccessible(true);
        return List.copyOf((List<Hologram>) field.get(handler));
    }
    private static Hologram create(GraveActionsTest.Harness h, ArmorStand stand) {
        when(h.world.spawnEntity(any(Location.class), eq(EntityType.ARMOR_STAND))).thenReturn(stand);
        return new Hologram(h.grave.getGravesConfig().getLocation().clone(), "grave", true, false);
    }
    @Test void claimManualRemovalAndExpiryReleaseAllRealWrappersWithoutGlobalEntityKill() throws Exception {
        for (String action : List.of("claim", "manual", "expiry")) try (var h = new GraveActionsTest.Harness();
                var core = mockStatic(AdvancedCorePlugin.class)) {
            core.when(AdvancedCorePlugin::getInstance).thenReturn(h.plugin);
            h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var state = new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192);
            var cleanup = new GraveMarkerCleanup(h.plugin, state);
            when(h.plugin.getMarkerCleanup()).thenReturn(cleanup); assertTrue(FeaturePersistenceTest.await(cleanup.loaded()));
            List<ArmorStand> owned = new ArrayList<>();
            for (String name : List.of("topHologram", "middleHologram", "bottomHologram", "glowingHologram")) {
                ArmorStand stand = mock(ArmorStand.class); owned.add(stand);
                field(h.grave, Grave.class, name, create(h, stand));
            }
            ArmorStand successor = mock(ArmorStand.class); Hologram unrelated = create(h, successor);
            assertEquals(5, registered(h.plugin.getHologramHandler()).size());
            h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(false); // Folia owner tasks are not Bukkit's primary thread.
            if (action.equals("claim")) {
                h.grave.claim(h.owner); h.drain();
                verify(h.owner).giveExp(17);
            } else {
                if (action.equals("expiry")) field(h.grave, Grave.class, "expiryDeadline", System.currentTimeMillis() - 1);
                h.grave.removeGrave(action.equals("expiry") ? GraveRemoveReason.EXPIRED : GraveRemoveReason.MANUAL_REMOVE);
            }
            h.regionWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            assertTrue(h.grave.isRemove()); assertTrue(state.snapshot().containsKey(h.identity()));
            assertEquals(List.of(unrelated), registered(h.plugin.getHologramHandler()));
            assertNull(h.grave.getTopHologram()); assertNull(h.grave.getMiddleHologram());
            assertNull(h.grave.getBottomHologram()); assertNull(h.grave.getGlowingHologram());
            for (ArmorStand stand : owned) {
                verify(stand, never()).remove(); verify(stand, never()).setHealth(anyDouble());
                verify(h.scheduler).runTask(eq(h.plugin), any(Runnable.class), eq(stand));
            }
            h.grave.removeHologram(); // Repeated/deferred cleanup cannot release a successor.
            while (!h.entityWork.isEmpty()) h.entityWork.remove().run();
            for (ArmorStand stand : owned) verify(stand).remove();
            verify(successor, never()).remove();
            verify(h.scheduler, never()).executeOrScheduleSync(any(), any());
            verify(h.world, never()).getChunkAt(anyInt(), anyInt());
            cleanup.retire();
        }
    }
    @Test void cancelledRemovalKeepsRealWrapperAndEntityRegistered() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var core = mockStatic(AdvancedCorePlugin.class)) {
            core.when(AdvancedCorePlugin::getInstance).thenReturn(h.plugin); h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            ArmorStand stand = mock(ArmorStand.class); Hologram hologram = create(h, stand);
            field(h.grave, Grave.class, "topHologram", hologram);
            var cleanup = new GraveMarkerCleanup(h.plugin, new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192));
            when(h.plugin.getMarkerCleanup()).thenReturn(cleanup); FeaturePersistenceTest.await(cleanup.loaded());
            doAnswer(call -> { ((GraveRemoveEvent) call.getArgument(0)).setCancelled(true); return null; }).when(h.events).callEvent(any());
            h.grave.removeGrave(GraveRemoveReason.MANUAL_REMOVE); h.regionWork.remove().run();
            assertFalse(h.grave.isRemove()); assertSame(hologram, h.grave.getTopHologram());
            assertEquals(List.of(hologram), registered(h.plugin.getHologramHandler())); verify(stand, never()).remove();
            assertTrue(h.entityWork.isEmpty()); cleanup.retire();
        }
    }
    @Test void legacyUnloadedCleanupStillUnregistersKnownWrapperWithoutLoadingChunk() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var core = mockStatic(AdvancedCorePlugin.class)) {
            core.when(AdvancedCorePlugin::getInstance).thenReturn(h.plugin); h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            ArmorStand stand = mock(ArmorStand.class); Hologram hologram = create(h, stand);
            field(h.grave, Grave.class, "topHologram", hologram);
            h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            when(h.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
            h.grave.removeGrave(GraveRemoveReason.MANUAL_REMOVE); h.regionWork.remove().run();
            assertNull(h.grave.getTopHologram()); assertTrue(registered(h.plugin.getHologramHandler()).isEmpty());
            verify(stand, never()).remove(); h.entityWork.remove().run(); verify(stand).remove();
            verify(h.world, never()).getChunkAt(anyInt(), anyInt()); verify(h.world, never()).getBlockAt(any(Location.class));
            verify(h.scheduler, never()).executeOrScheduleSync(any(), any());
        }
    }
    @Test void lateEntityCreationAfterDurableRemovalUnregistersAndUsesEntityOwner() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var core = mockStatic(AdvancedCorePlugin.class)) {
            core.when(AdvancedCorePlugin::getInstance).thenReturn(h.plugin); h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            ArmorStand stand = mock(ArmorStand.class); Hologram hologram = create(h, stand);
            field(hologram, Hologram.class, "armorStand", null); field(h.grave, Grave.class, "topHologram", hologram);
            doAnswer(call -> { h.regionWork.add(call.getArgument(1)); return null; }).when(h.scheduler)
                    .runTaskLater(eq(h.plugin), any(Runnable.class), eq(1L), any(Location.class));
            h.grave.tagHologram(hologram, 0); Runnable lateTag = h.regionWork.remove();
            var cleanup = new GraveMarkerCleanup(h.plugin, new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192));
            when(h.plugin.getMarkerCleanup()).thenReturn(cleanup); FeaturePersistenceTest.await(cleanup.loaded());
            h.grave.removeGrave(GraveRemoveReason.MANUAL_REMOVE); h.regionWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            assertTrue(registered(h.plugin.getHologramHandler()).isEmpty());
            field(hologram, Hologram.class, "armorStand", stand); h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            lateTag.run(); verify(stand, never()).remove(); h.entityWork.remove().run(); verify(stand).remove();
            assertTrue(registered(h.plugin.getHologramHandler()).isEmpty());
            verify(stand, never()).getPersistentDataContainer(); verify(h.scheduler, never()).executeOrScheduleSync(any(), any());
            cleanup.retire();
        }
    }
    @Test void invalidGlowRefreshDoesNotReleaseWrappersBeforeCancellableRemoval() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var core = mockStatic(AdvancedCorePlugin.class)) {
            core.when(AdvancedCorePlugin::getInstance).thenReturn(h.plugin); h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            ArmorStand stand = mock(ArmorStand.class); Hologram hologram = create(h, stand);
            field(h.grave, Grave.class, "topHologram", hologram);
            var block = mock(org.bukkit.block.Block.class); when(block.getType()).thenReturn(Material.AIR);
            when(h.world.getBlockAt(any(Location.class))).thenReturn(block); when(h.world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
            when(h.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true); when(h.config.isGlowingEffectNearGrave()).thenReturn(true);
            doCallRealMethod().when(h.plugin).refreshGraveGlow(any());
            doAnswer(call -> { ((GraveRemoveEvent) call.getArgument(0)).setCancelled(true); return null; }).when(h.events).callEvent(any());
            h.plugin.refreshGraveGlow(h.grave); h.regionWork.remove().run();
            assertSame(hologram, h.grave.getTopHologram()); assertEquals(List.of(hologram), registered(h.plugin.getHologramHandler()));
            h.regionWork.remove().run(); assertFalse(h.grave.isRemove());
            assertSame(hologram, h.grave.getTopHologram()); assertEquals(List.of(hologram), registered(h.plugin.getHologramHandler()));
            verify(stand, never()).remove(); assertTrue(h.entityWork.isEmpty());
        }
    }
    @Test void glowRemovalLeavesRealRegistryEmptyForShutdownWithoutGlobalEntityKill() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var core = mockStatic(AdvancedCorePlugin.class)) {
            core.when(AdvancedCorePlugin::getInstance).thenReturn(h.plugin); h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            ArmorStand stand = mock(ArmorStand.class); Hologram hologram = create(h, stand);
            field(h.grave, Grave.class, "glowingHologram", hologram);
            h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            h.grave.checkGlowing(); // Disabled glow uses the same unregister-only release.
            assertNull(h.grave.getGlowingHologram()); assertTrue(registered(h.plugin.getHologramHandler()).isEmpty());
            h.entityWork.remove().run(); verify(stand).remove();
            assertDoesNotThrow(h.plugin.getHologramHandler()::onShutDown);
            verify(h.scheduler, never()).executeOrScheduleSync(any(), any());
        }
    }
    @Test void lateTagOfReplacedWrapperRemovesOnlyItsOriginalStand() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var core = mockStatic(AdvancedCorePlugin.class)) {
            core.when(AdvancedCorePlugin::getInstance).thenReturn(h.plugin); h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            ArmorStand previous = mock(ArmorStand.class), current = mock(ArmorStand.class);
            Hologram old = create(h, previous), replacement = create(h, current);
            field(h.grave, Grave.class, "topHologram", replacement);
            h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);
            h.grave.tagHologram(old, 1);
            assertEquals(List.of(replacement), registered(h.plugin.getHologramHandler()));
            verify(previous, never()).remove(); h.entityWork.remove().run(); verify(previous).remove();
            verify(previous, never()).getPersistentDataContainer(); verify(current, never()).remove();
            assertSame(replacement, h.grave.getTopHologram());
            verify(h.scheduler, never()).executeOrScheduleSync(any(), any());
        }
    }
}
