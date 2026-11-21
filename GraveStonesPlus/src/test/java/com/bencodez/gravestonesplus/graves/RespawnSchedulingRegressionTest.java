package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.function.Consumer;
import com.bencodez.gravestonesplus.breaking.PlayerBreakManager;
import com.bencodez.gravestonesplus.listeners.PlayerDeathListener;
import com.bencodez.simpleapi.folialib.impl.PaperImplementation;
import com.bencodez.simpleapi.folialib.impl.SpigotImplementation;
import com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

class RespawnSchedulingRegressionTest {
    /** Real SimpleAPI scheduling methods and implementation; only native Bukkit queues are controlled. */
    private static final class BundledScheduler {
        final org.bukkit.scheduler.BukkitScheduler nativeScheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        final Queue<Runnable> delayed = new ArrayDeque<>();
        @SuppressWarnings("unchecked")
        BundledScheduler(GraveActionsTest.Harness h, boolean paper) {
            Server server = mock(Server.class);
            when(h.plugin.getServer()).thenReturn(server); when(server.getScheduler()).thenReturn(nativeScheduler);
            when(h.folia.getPlugin()).thenReturn(h.plugin);
            var implementation = paper ? new PaperImplementation(h.folia) : new SpigotImplementation(h.folia);
            when(h.folia.getImpl()).thenReturn(implementation);
            var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class, CALLS_REAL_METHODS);
            when(scheduler.getFoliaLib()).thenReturn(h.folia); when(h.plugin.getBukkitScheduler()).thenReturn(scheduler);
            // The old entity-delay overload must see a valid entity to expose its recursion.
            when(h.owner.isValid()).thenReturn(true); when(h.helper.isValid()).thenReturn(true);
            when(nativeScheduler.runTaskLater(eq(h.plugin), any(Runnable.class), anyLong())).thenAnswer(call -> {
                delayed.add(call.getArgument(1)); return mock(BukkitTask.class);
            });
            doAnswer(call -> {
                Consumer<BukkitTask> action = call.getArgument(1);
                delayed.add(() -> action.accept(mock(BukkitTask.class))); return null;
            }).when(nativeScheduler).runTaskLater(eq(h.plugin), any(Consumer.class), anyLong());
            doAnswer(call -> {
                Consumer<BukkitTask> action = call.getArgument(1);
                h.entityWork.add(() -> action.accept(mock(BukkitTask.class))); return null;
            }).when(nativeScheduler).runTask(eq(h.plugin), any(Consumer.class));
        }
    }
    private static MockedConstruction<ItemStack> compasses() {
        return mockConstruction(ItemStack.class, (item, context) -> {
            CompassMeta meta = mock(CompassMeta.class);
            when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            when(item.getItemMeta()).thenReturn(meta);
        });
    }
    private static void respawn(GraveActionsTest.Harness h, Player player) {
        when(h.config.isGiveCompassOnRespawn()).thenReturn(true);
        when(h.plugin.getLatestGrave(player)).thenReturn(h.grave);
        when(player.hasPermission("GraveStonesPlus.Compass")).thenReturn(true);
        PlayerRespawnEvent event = mock(PlayerRespawnEvent.class); when(event.getPlayer()).thenReturn(player);
        new PlayerDeathListener(h.plugin).onRespawn(event);
    }
    @ParameterizedTest @ValueSource(booleans = { false, true })
    void bundledSpigotAndPaperDeliverOnlyAfterTheOriginalFiveSecondOwnerDelay(boolean paper) throws Exception {
        try (var h = new GraveActionsTest.Harness(); var items = compasses()) {
            var scheduler = new BundledScheduler(h, paper);
            when(h.ownerInventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
            assertDoesNotThrow(() -> respawn(h, h.owner));
            verify(scheduler.nativeScheduler).runTaskLater(eq(h.plugin), any(Runnable.class), eq(5L));
            assertEquals(1, scheduler.delayed.size()); assertTrue(items.constructed().isEmpty()); assertTrue(h.entityWork.isEmpty());
            scheduler.delayed.remove().run(); assertTrue(items.constructed().isEmpty()); assertEquals(1, h.entityWork.size());
            h.drain(); assertEquals(1, items.constructed().size()); verify(h.ownerInventory).addItem(items.constructed().getFirst());
            assertSame(h.grave, h.navigation.target(h.owner));
            verify(scheduler.nativeScheduler, never()).runTaskLaterAsynchronously(any(), any(Runnable.class), anyLong());
        }
    }
    @ParameterizedTest @ValueSource(booleans = { false, true })
    void bundledDelayedCallbackRejectsPermissionOfflineDisableAndGraveAccessChanges(boolean paper) throws Exception {
        for (String change : List.of("permission", "offline", "disabled", "removed", "trust")) {
            try (var h = new GraveActionsTest.Harness(); var items = compasses()) {
                var scheduler = new BundledScheduler(h, paper);
                h.grant(TrustedGraveAccess.Kind.TRACK); respawn(h, h.helper);
                switch (change) {
                    case "permission" -> when(h.helper.hasPermission("GraveStonesPlus.Compass")).thenReturn(false);
                    case "offline" -> when(h.helper.isOnline()).thenReturn(false);
                    case "disabled" -> when(h.plugin.isEnabled()).thenReturn(false);
                    case "removed" -> h.grave.getGravesConfig().setDestroyed(true);
                    case "trust" -> FeaturePersistenceTest.await(h.access.revokeAsync(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK));
                }
                scheduler.delayed.remove().run(); assertTrue(h.entityWork.isEmpty()); h.drain();
                assertTrue(items.constructed().isEmpty()); verify(h.helperInventory, never()).addItem(any(ItemStack[].class));
            }
        }
    }
    @Test void foliaRespawnRetainsTheMovingPlayerEntityDelayAndRetirementBoundary() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var items = compasses()) {
            when(h.folia.isFolia()).thenReturn(true);
            List<Runnable> delayed = new ArrayList<>(), retired = new ArrayList<>();
            when(h.platform.runAtEntityLater(eq(h.owner), any(Runnable.class), any(Runnable.class), eq(5L))).thenAnswer(call -> {
                delayed.add(call.getArgument(1)); retired.add(call.getArgument(2)); return mock(WrappedTask.class);
            });
            respawn(h, h.owner); assertEquals(1, delayed.size()); retired.getFirst().run();
            assertTrue(h.entityWork.isEmpty()); assertTrue(items.constructed().isEmpty());
            verify(h.platform, never()).runLater(any(Runnable.class), anyLong(), any());
            // A later admitted respawn remains guarded at the final delivery callback.
            respawn(h, h.owner); delayed.get(1).run(); assertEquals(1, h.entityWork.size());
            when(h.owner.hasPermission("GraveStonesPlus.Compass")).thenReturn(false); h.drain();
            assertTrue(items.constructed().isEmpty()); verify(h.ownerInventory, never()).addItem(any(ItemStack[].class));
            doReturn(null).when(h.platform).runAtEntityLater(eq(h.owner), any(Runnable.class), any(Runnable.class), eq(5L));
            respawn(h, h.owner); assertTrue(h.entityWork.isEmpty()); assertEquals(2, delayed.size());
            assertTrue(items.constructed().isEmpty());
        }
    }
    @ParameterizedTest @ValueSource(booleans = { false, true })
    @SuppressWarnings("unchecked")
    void ownerBreakCompletionKeepsItsOriginalUuidOnlyTwentySecondCleanup(boolean paper) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var scheduler = new BundledScheduler(h, paper);
            var manager = new PlayerBreakManager(h.plugin);
            when(h.plugin.getPlayerBreakManager()).thenReturn(manager);
            when(h.config.getBreakOwnGraveTime()).thenReturn(RecoveryCompletionTest.duration("1s"));
            when(h.config.getBreakOwnGraveHitTimeout()).thenReturn(RecoveryCompletionTest.duration("1m"));
            assertFalse(manager.handleOwnerHit(h.owner, h.grave));
            manager.getOwnerActiveBreaks().get(h.owner.getUniqueId()).setStartTime(System.currentTimeMillis() - 2000);
            assertDoesNotThrow(() -> assertTrue(manager.handleOwnerHit(h.owner, h.grave)));
            // Both existing completion checkpoints schedule seconds, independent of a live entity.
            verify(scheduler.nativeScheduler, times(2)).runTaskLater(eq(h.plugin), any(Consumer.class), eq(400L));
            assertEquals(2, scheduler.delayed.size()); assertFalse(h.entityWork.isEmpty());
            when(h.owner.isOnline()).thenReturn(false);
            long newerCompletion = System.currentTimeMillis();
            manager.getOwnerLastCompleted().put(h.owner.getUniqueId(), newerCompletion);
            scheduler.delayed.remove().run();
            assertEquals(newerCompletion, manager.getOwnerLastCompleted().get(h.owner.getUniqueId()));
            manager.getOwnerLastCompleted().put(h.owner.getUniqueId(), System.currentTimeMillis() - 2000);
            scheduler.delayed.remove().run(); assertTrue(manager.getOwnerLastCompleted().isEmpty());
        }
    }
}
