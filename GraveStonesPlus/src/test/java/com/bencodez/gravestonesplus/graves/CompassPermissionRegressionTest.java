package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.gravestonesplus.commands.CommandLoader;
import com.bencodez.gravestonesplus.listeners.PlayerDeathListener;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

class CompassPermissionRegressionTest {
    private static final class Commands {
        final Queue<Runnable> work = new ConcurrentLinkedQueue<>();
        final ArrayList<CommandHandler> handlers = new ArrayList<>();
        Commands(GraveActionsTest.Harness h) {
            var options = mock(AdvancedCoreConfigOptions.class); when(options.getFormatNoPerms()).thenReturn("Denied");
            when(h.plugin.getOptions()).thenReturn(options); when(h.plugin.getCommands()).thenReturn(handlers);
            doAnswer(call -> { work.add(call.getArgument(1)); return null; }).when(h.scheduler)
                    .runTaskAsynchronously(eq(h.plugin), any(Runnable.class));
            new CommandLoader(h.plugin).loadCommands();
            Block target = mock(Block.class); when(target.getWorld()).thenReturn(h.world);
            when(target.getX()).thenReturn(1); when(target.getY()).thenReturn(64); when(target.getZ()).thenReturn(1);
            when(h.owner.getTargetBlock(isNull(), eq(7))).thenReturn(target);
            when(h.helper.getTargetBlock(isNull(), eq(7))).thenReturn(target);
            when(h.plugin.getLatestGrave(h.owner)).thenReturn(h.grave);
        }
        CommandHandler get(String name) {
            return handlers.stream().filter(command -> command.getArgs()[0].equalsIgnoreCase(name)).findFirst().orElseThrow();
        }
        void run(Player player, String name) {
            assertTrue(get(name).runCommand(player, new String[] { name }));
            if (!work.isEmpty()) work.remove().run();
        }
    }
    private static MockedConstruction<ItemStack> compasses() {
        return mockConstruction(ItemStack.class, (item, context) -> {
            CompassMeta meta = mock(CompassMeta.class); when(meta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
            when(item.getItemMeta()).thenReturn(meta);
        });
    }
    @Test void actualTrackCommandSelectsForOwnerAndTrustedHelperWithoutCompassPermission() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var items = compasses()) {
            Commands commands = new Commands(h); h.grant(TrustedGraveAccess.Kind.TRACK);
            for (Player player : List.of(h.owner, h.helper)) {
                when(player.hasPermission("GraveStonesPlus.Track")).thenReturn(true);
                when(player.hasPermission("GraveStonesPlus.Compass")).thenReturn(false);
                commands.run(player, "track");
                verify(player, never()).getTargetBlock(any(), anyInt()); // Dispatcher has only scheduled the actor task.
                h.drain(); assertSame(h.grave, h.navigation.target(player));
                verify(player).sendMessage(argThat((String message) -> message.contains("Selected grave:")));
                commands.run(player, "compass"); h.drain();
            }
            assertTrue(items.constructed().isEmpty()); verify(h.ownerInventory, never()).addItem(any(ItemStack[].class));
            verify(h.helperInventory, never()).addItem(any(ItemStack[].class)); verify(h.plugin, never()).getFullInventoryHandler();
        }
    }
    @Test void actualTrackCommandRechecksTrackAndTrustAtActorBoundary() throws Exception {
        for (boolean revokeTrust : List.of(false, true)) try (var h = new GraveActionsTest.Harness(); var items = compasses()) {
            Commands commands = new Commands(h); h.grant(TrustedGraveAccess.Kind.TRACK);
            when(h.helper.hasPermission("GraveStonesPlus.Track")).thenReturn(true);
            commands.run(h.helper, "track");
            if (revokeTrust) FeaturePersistenceTest.await(h.access.revokeAsync(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK));
            else when(h.helper.hasPermission("GraveStonesPlus.Track")).thenReturn(false);
            h.drain(); assertNull(h.navigation.target(h.helper)); assertTrue(items.constructed().isEmpty());
            verify(h.helperInventory, never()).addItem(any(ItemStack[].class));
        }
    }
    @Test void actualCompassCommandRechecksRevokedPermissionBeforeAnyItemDelivery() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var items = compasses()) {
            Commands commands = new Commands(h); when(h.owner.hasPermission("GraveStonesPlus.Compass")).thenReturn(true);
            commands.run(h.owner, "compass"); assertFalse(h.entityWork.isEmpty());
            when(h.owner.hasPermission("GraveStonesPlus.Compass")).thenReturn(false); h.drain();
            assertTrue(items.constructed().isEmpty()); verify(h.ownerInventory, never()).addItem(any(ItemStack[].class));
            verify(h.plugin, never()).getFullInventoryHandler();
        }
    }
    @Test void directDeferredDeliveryRejectsRevokedTrustRemovedGraveAndOfflineOrDisabledRecipient() throws Exception {
        for (String change : List.of("trust", "removed", "offline", "disabled")) try (var h = new GraveActionsTest.Harness(); var items = compasses()) {
            h.grant(TrustedGraveAccess.Kind.TRACK); when(h.helper.hasPermission("GraveStonesPlus.Compass")).thenReturn(true);
            h.grave.giveCompass(h.helper);
            switch (change) {
                case "trust" -> FeaturePersistenceTest.await(h.access.revokeAsync(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK));
                case "removed" -> h.grave.getGravesConfig().setDestroyed(true);
                case "offline" -> when(h.helper.isOnline()).thenReturn(false);
                case "disabled" -> when(h.plugin.isEnabled()).thenReturn(false);
            }
            h.drain(); assertTrue(items.constructed().isEmpty()); verify(h.helperInventory, never()).addItem(any(ItemStack[].class));
        }
    }
    @Test void menuDeliveryRechecksPermissionAfterSuccessfulClickAdmission() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var items = compasses();
                var builders = mockConstruction(com.bencodez.advancedcore.api.item.ItemBuilder.class, withSettings().defaultAnswer(RETURNS_SELF))) {
            when(h.owner.hasPermission("GraveStonesPlus.Compass")).thenReturn(true);
            var click = mock(com.bencodez.advancedcore.api.inventory.BInventory.ClickEvent.class);
            when(click.getPlayer()).thenReturn(h.owner); when(click.getClick()).thenReturn(ClickType.LEFT);
            h.grave.getNavigationGUIItem(h.owner).onClick(click); assertFalse(h.entityWork.isEmpty());
            when(h.owner.hasPermission("GraveStonesPlus.Compass")).thenReturn(false); h.drain();
            assertTrue(items.constructed().isEmpty()); verify(h.ownerInventory, never()).addItem(any(ItemStack[].class));
        }
    }
    @Test void respawnUsesPlayerOwnerAndRevalidatesPermissionAfterDelay() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var items = compasses()) {
            when(h.config.isGiveCompassOnRespawn()).thenReturn(true); when(h.plugin.getLatestGrave(h.owner)).thenReturn(h.grave);
            when(h.owner.hasPermission("GraveStonesPlus.Compass")).thenReturn(true);
            Queue<Runnable> delayed = new ConcurrentLinkedQueue<>();
            when(h.platform.runLater(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS))).thenAnswer(call -> {
                delayed.add(call.getArgument(0)); return mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class);
            });
            PlayerRespawnEvent event = mock(PlayerRespawnEvent.class); when(event.getPlayer()).thenReturn(h.owner);
            new PlayerDeathListener(h.plugin).onRespawn(event); assertEquals(1, delayed.size());
            delayed.remove().run(); when(h.owner.hasPermission("GraveStonesPlus.Compass")).thenReturn(false); h.drain();
            assertTrue(items.constructed().isEmpty()); verify(h.ownerInventory, never()).addItem(any(ItemStack[].class));
            verify(h.scheduler, never()).runTask(eq(h.plugin), any(Runnable.class));
            verify(h.scheduler, never()).runTaskLaterAsynchronously(any(), any(), anyLong());
        }
    }
    @Test void permittedCompassDeliveryHasNoFurtherUnguardedSchedulerHop() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var items = compasses()) {
            Commands commands = new Commands(h); when(h.owner.hasPermission("GraveStonesPlus.Compass")).thenReturn(true);
            when(h.ownerInventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
            commands.run(h.owner, "compass"); h.entityWork.remove().run();
            assertEquals(1, items.constructed().size()); verify(h.ownerInventory).addItem(items.constructed().getFirst());
            assertSame(h.grave, h.navigation.target(h.owner)); assertTrue(h.entityWork.isEmpty());
            verify(h.plugin, never()).getFullInventoryHandler();
        }
    }
    @Test void fullInventoryCompassHonorsDropPolicyAndNeverQueuesLaterDelivery() throws Exception {
        for (boolean drop : List.of(false, true)) try (var h = new GraveActionsTest.Harness(); var items = compasses()) {
            var options = mock(AdvancedCoreConfigOptions.class); when(options.isDropOnFullInv()).thenReturn(drop); when(h.plugin.getOptions()).thenReturn(options);
            when(h.owner.hasPermission("GraveStonesPlus.Compass")).thenReturn(true);
            when(h.ownerInventory.addItem(any(ItemStack[].class))).thenAnswer(call -> new HashMap<>(Map.of(0, ((ItemStack[]) call.getRawArguments()[0])[0])));
            h.grave.giveCompass(h.owner); h.entityWork.remove().run();
            assertEquals(1, items.constructed().size()); assertSame(h.grave, h.navigation.target(h.owner)); assertTrue(h.entityWork.isEmpty());
            verify(h.world, times(drop ? 1 : 0)).dropItem(h.owner.getLocation(), items.constructed().getFirst());
            if (!drop) verify(h.owner).sendMessage(argThat((String message) -> message.contains("Make room")));
            verify(h.plugin, never()).getFullInventoryHandler();
        }
    }
}
