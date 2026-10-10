package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.gravestonesplus.breaking.PlayerBreakManager;
import com.bencodez.gravestonesplus.commands.CommandLoader;
import com.bencodez.simpleapi.command.TabCompleteHandler;
import com.bencodez.simpleapi.folialib.impl.SpigotImplementation;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

class SelectedCommandDispatchTest {
    /** Real bundled command and SimpleAPI scheduler code through controlled native queues. */
    private static final class Commands {
        final GraveActionsTest.Harness h;
        final Queue<Runnable> async = new ConcurrentLinkedQueue<>();
        final ArrayList<CommandHandler> handlers = new ArrayList<>();
        final ThreadLocal<String> phase = ThreadLocal.withInitial(() -> "admission");
        final PlayerBreakManager manager;
        int targetReads, inventoryReads;
        @SuppressWarnings("unchecked")
        Commands(GraveActionsTest.Harness h) {
            this.h = h;
            manager = new PlayerBreakManager(h.plugin); when(h.plugin.getPlayerBreakManager()).thenReturn(manager);
            var options = mock(AdvancedCoreConfigOptions.class); when(options.getFormatNoPerms()).thenReturn("Denied");
            when(h.plugin.getOptions()).thenReturn(options); when(h.plugin.getCommands()).thenReturn(handlers);
            when(h.owner.hasPermission(anyString())).thenReturn(true);
            when(h.owner.getName()).thenReturn("Owner"); when(h.owner.getDisplayName()).thenReturn("Owner");
            when(h.helper.getName()).thenReturn("Alex");
            h.bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(h.owner, h.helper));
            h.bukkit.when(() -> Bukkit.getPlayerExact("Alex")).thenAnswer(call -> { ownerOnly(); return h.helper; });
            Server server = mock(Server.class);
            var nativeScheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
            when(h.plugin.getServer()).thenReturn(server); when(server.getScheduler()).thenReturn(nativeScheduler);
            when(h.folia.getPlugin()).thenReturn(h.plugin);
            var implementation = new SpigotImplementation(h.folia);
            when(h.folia.getImpl()).thenReturn(implementation);
            var scheduler = mock(com.bencodez.simpleapi.scheduler.BukkitScheduler.class, CALLS_REAL_METHODS);
            when(scheduler.getFoliaLib()).thenReturn(h.folia); when(h.plugin.getBukkitScheduler()).thenReturn(scheduler);
            doAnswer(call -> {
                Consumer<BukkitTask> work = call.getArgument(1);
                async.add(() -> runAs("async", () -> work.accept(mock(BukkitTask.class)))); return null;
            }).when(nativeScheduler).runTaskAsynchronously(eq(h.plugin), any(Consumer.class));
            doAnswer(call -> {
                Consumer<BukkitTask> work = call.getArgument(1);
                h.entityWork.add(() -> runAs("owner", () -> work.accept(mock(BukkitTask.class)))); return null;
            }).when(nativeScheduler).runTask(eq(h.plugin), any(Consumer.class));
            Block target = mock(Block.class);
            when(target.getWorld()).thenAnswer(call -> { ownerOnly(); return h.world; });
            when(target.getX()).thenAnswer(call -> { ownerOnly(); return 1; });
            when(target.getY()).thenAnswer(call -> { ownerOnly(); return 64; });
            when(target.getZ()).thenAnswer(call -> { ownerOnly(); return 1; });
            when(h.owner.getTargetBlock(isNull(), eq(7))).thenAnswer(call -> { ownerOnly(); targetReads++; return target; });
            when(h.owner.getInventory()).thenAnswer(call -> { ownerOnly(); inventoryReads++; return h.ownerInventory; });
            when(h.ownerInventory.getItemInMainHand()).thenReturn(h.stored);
            when(h.config.getKeepItemsWithLore()).thenReturn("");
            doAnswer(call -> { ownerOnly(); return h.cloned; }).when(h.stored).clone();
            new CommandLoader(h.plugin).loadCommands();
            TabCompleteHandler.getInstance().onLogin();
        }
        void ownerOnly() { assertEquals("owner", phase.get(), "Player/world/item read must run on the entity owner"); }
        void runAs(String selected, Runnable work) {
            String previous = phase.get(); phase.set(selected);
            try { work.run(); } finally { phase.set(previous); }
        }
        CommandHandler get(String name) {
            return handlers.stream().filter(command -> command.getArgs()[0].equalsIgnoreCase(name)).findFirst().orElseThrow();
        }
        void dispatch(String... args) { dispatch(h.owner, args); }
        void dispatch(org.bukkit.entity.Player actor, String... args) {
            assertTrue(get(args[0]).runCommand(actor, args));
            assertEquals(1, async.size()); assertTrue(h.entityWork.isEmpty());
            async.remove().run(); assertTrue(async.isEmpty()); assertEquals(1, h.entityWork.size());
            assertEquals(0, targetReads); assertEquals(0, inventoryReads);
        }
        int locationReads, worldReads;
        void guardNavigation(org.bukkit.entity.Player actor, java.util.concurrent.atomic.AtomicReference<org.bukkit.Location> position) {
            doAnswer(call -> { ownerOnly(); locationReads++; return position.get().clone(); }).when(actor).getLocation();
            doAnswer(call -> { ownerOnly(); worldReads++; return position.get().getWorld(); }).when(actor).getWorld();
            doAnswer(call -> { ownerOnly(); return null; }).when(actor).sendMessage(anyString());
            doAnswer(call -> { ownerOnly(); inventoryReads++; return actor == h.owner ? h.ownerInventory : h.helperInventory; }).when(actor).getInventory();
            when(actor.isValid()).thenReturn(true);
        }
        void resetReadCounts() { targetReads = 0; inventoryReads = 0; locationReads = 0; worldReads = 0; }
    }
    @Test void actualGravesDispatcherDefersNavigationRenderingAndInventoryToThePlayerOwner() throws Exception {
        try (var h = new GraveActionsTest.Harness();
                var builders = mockConstruction(com.bencodez.advancedcore.api.item.ItemBuilder.class, withSettings().defaultAnswer(RETURNS_SELF))) {
            var commands = new Commands(h);
            var position = new java.util.concurrent.atomic.AtomicReference<>(new org.bukkit.Location(h.world, 1, 64, 1));
            commands.guardNavigation(h.owner, position);
            var buttons = new ArrayList<com.bencodez.advancedcore.api.inventory.BInventoryButton>();
            try (var menus = mockConstruction(com.bencodez.advancedcore.api.inventory.BInventory.class, (menu, context) -> {
                doAnswer(call -> { commands.ownerOnly(); buttons.add(call.getArgument(0)); return null; }).when(menu).addButton(any());
                doAnswer(call -> { commands.ownerOnly(); return null; }).when(menu).openInventory(h.owner);
            })) {
                commands.dispatch("Graves"); assertTrue(menus.constructed().isEmpty()); assertEquals(0, commands.locationReads);
                position.set(new org.bukkit.Location(h.world, 5, 60, 1)); h.drain();
                assertEquals(1, commands.locationReads); assertEquals(0, commands.worldReads); assertEquals(1, buttons.size());
                verify(menus.constructed().getFirst()).openInventory(h.owner);
                verify(builders.constructed().getFirst()).addLoreLine("&7overworld, 4 blocks, above");
            }
        }
    }
    @Test void navigationRenderRechecksAccessPermissionRetirementAndReloadAfterDispatcherDelay() throws Exception {
        for (String change : List.of("trust", "removed", "permission", "offline", "disabled", "reload")) try (var h = new GraveActionsTest.Harness();
                var builders = mockConstruction(com.bencodez.advancedcore.api.item.ItemBuilder.class, withSettings().defaultAnswer(RETURNS_SELF));
                var menus = mockConstruction(com.bencodez.advancedcore.api.inventory.BInventory.class)) {
            h.grant(TrustedGraveAccess.Kind.TRACK); when(h.helper.hasPermission("GraveStonesPlus.Graves")).thenReturn(true);
            var commands = new Commands(h); commands.guardNavigation(h.helper,
                    new java.util.concurrent.atomic.AtomicReference<>(new org.bukkit.Location(h.world, 1, 64, 1)));
            commands.dispatch(h.helper, "Graves");
            switch (change) {
                case "trust" -> FeaturePersistenceTest.await(h.access.revokeAsync(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK));
                case "removed" -> h.grave.getGravesConfig().setDestroyed(true);
                case "permission" -> when(h.helper.hasPermission("GraveStonesPlus.Graves")).thenReturn(false);
                case "offline" -> when(h.helper.isOnline()).thenReturn(false);
                case "disabled" -> when(h.plugin.isEnabled()).thenReturn(false);
                case "reload" -> commands.manager.invalidateAuthorization();
            }
            h.drain(); assertTrue(builders.constructed().isEmpty()); assertEquals(0, commands.locationReads);
            if (change.equals("trust") || change.equals("removed")) {
                assertEquals(1, menus.constructed().size()); verify(menus.constructed().getFirst(), never()).addButton(any());
            } else assertTrue(menus.constructed().isEmpty());
            verify(h.helperInventory, never()).addItem(any(ItemStack[].class));
        }
    }
    private static void asyncMenuClick(Commands commands, GraveActionsTest.Harness h,
            com.bencodez.advancedcore.api.inventory.BInventoryButton button, org.bukkit.entity.Player actor,
            org.bukkit.event.inventory.ClickType click) {
        var menu = new com.bencodez.advancedcore.api.inventory.BInventory("Native click").noSound();
        assertTrue(menu.isClickAsync(), "the native menu default is the reported asynchronous boundary");
        var nativeClick = mock(org.bukkit.event.inventory.InventoryClickEvent.class);
        when(nativeClick.getWhoClicked()).thenReturn(actor); when(nativeClick.getClick()).thenReturn(click);
        commands.resetReadCounts();
        commands.runAs("async", () -> menu.onClick(nativeClick, button));
        assertEquals(0, commands.locationReads); assertEquals(0, commands.worldReads); assertEquals(0, commands.inventoryReads);
        assertEquals(1, h.entityWork.size());
    }
    @Test void nativeNavigationClickSnapshotsIntentAndUsesPostTeleportOwnerPosition() throws Exception {
        try (var h = new GraveActionsTest.Harness();
                var builders = mockConstruction(com.bencodez.advancedcore.api.item.ItemBuilder.class, withSettings().defaultAnswer(RETURNS_SELF))) {
            var commands = new Commands(h); when(h.config.getGraveClaimDistance()).thenReturn(10);
            var position = new java.util.concurrent.atomic.AtomicReference<>(new org.bukkit.Location(h.world, 1, 64, 1));
            commands.guardNavigation(h.owner, position);
            var button = new java.util.concurrent.atomic.AtomicReference<com.bencodez.advancedcore.api.inventory.BInventoryButton>();
            commands.runAs("owner", () -> button.set(h.grave.getNavigationGUIItem(h.owner)));
            asyncMenuClick(commands, h, button.get(), h.owner, org.bukkit.event.inventory.ClickType.RIGHT);
            var other = mock(org.bukkit.World.class); when(other.getUID()).thenReturn(UUID.randomUUID());
            position.set(new org.bukkit.Location(other, 1, 64, 1)); h.drain();
            assertEquals(1, commands.locationReads); assertEquals(0, commands.worldReads);
            verify(h.owner).sendMessage(argThat((String message) -> message.contains("Move near")));
            verify(h.owner, never()).giveExp(anyInt()); verify(h.ownerInventory, never()).setItem(anyInt(), any());
            assertFalse(h.ledger.isBlocked(h.identity()));
        }
    }
    @Test void nativeNavigationClickRechecksTrustRecoveryLiveAndRetiredActorStateBeforeEffects() throws Exception {
        for (String change : List.of("track", "recover", "removed", "offline", "disabled", "permission")) try (var h = new GraveActionsTest.Harness();
                var builders = mockConstruction(com.bencodez.advancedcore.api.item.ItemBuilder.class, withSettings().defaultAnswer(RETURNS_SELF))) {
            h.grant(TrustedGraveAccess.Kind.TRACK); h.grant(TrustedGraveAccess.Kind.RECOVER);
            when(h.helper.hasPermission("GraveStonesPlus.ClaimGrave")).thenReturn(true);
            var commands = new Commands(h); when(h.config.getGraveClaimDistance()).thenReturn(10);
            commands.guardNavigation(h.helper, new java.util.concurrent.atomic.AtomicReference<>(new org.bukkit.Location(h.world, 1, 64, 1)));
            var button = new java.util.concurrent.atomic.AtomicReference<com.bencodez.advancedcore.api.inventory.BInventoryButton>();
            commands.runAs("owner", () -> button.set(h.grave.getNavigationGUIItem(h.helper)));
            asyncMenuClick(commands, h, button.get(), h.helper, org.bukkit.event.inventory.ClickType.RIGHT);
            switch (change) {
                case "track" -> FeaturePersistenceTest.await(h.access.revokeAsync(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK));
                case "recover" -> FeaturePersistenceTest.await(h.access.revokeAsync(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER));
                case "removed" -> h.grave.getGravesConfig().setDestroyed(true);
                case "offline" -> when(h.helper.isOnline()).thenReturn(false);
                case "disabled" -> when(h.plugin.isEnabled()).thenReturn(false);
                case "permission" -> when(h.helper.hasPermission("GraveStonesPlus.ClaimGrave")).thenReturn(false);
            }
            h.drain(); assertEquals(change.equals("recover") ? 1 : 0, commands.locationReads);
            verify(h.helper, never()).giveExp(anyInt()); verify(h.helperInventory, never()).setItem(anyInt(), any());
            assertFalse(h.ledger.isBlocked(h.identity()));
        }
    }
    @Test void nativeNavigationRecoveryAndLegacyAdminTeleportKeepTheirExistingEffectsOnTheOwner() throws Exception {
        for (boolean navigation : List.of(false, true)) try (var h = new GraveActionsTest.Harness();
                var builders = mockConstruction(com.bencodez.advancedcore.api.item.ItemBuilder.class, withSettings().defaultAnswer(RETURNS_SELF))) {
            var commands = new Commands(h); when(h.config.getGraveClaimDistance()).thenReturn(10);
            var actor = navigation ? h.owner : h.helper;
            if (!navigation) when(h.helper.hasPermission("GraveStonesPlus.AllGraves")).thenReturn(true);
            commands.guardNavigation(actor, new java.util.concurrent.atomic.AtomicReference<>(new org.bukkit.Location(h.world, 1, 64, 1)));
            doAnswer(call -> { commands.ownerOnly(); return true; }).when(actor).teleport(any(org.bukkit.Location.class));
            var button = new java.util.concurrent.atomic.AtomicReference<com.bencodez.advancedcore.api.inventory.BInventoryButton>();
            commands.runAs("owner", () -> button.set(navigation ? h.grave.getNavigationGUIItem(actor) : h.grave.getGUIItem()));
            asyncMenuClick(commands, h, button.get(), actor, navigation ? org.bukkit.event.inventory.ClickType.RIGHT : org.bukkit.event.inventory.ClickType.LEFT);
            h.drain();
            if (navigation) { verify(actor).giveExp(17); verify(h.ownerInventory).setItem(4, h.cloned); }
            else { verify(actor).teleport(h.grave.getGravesConfig().getLocation().clone().add(0, 1, 0)); verify(actor, never()).giveExp(anyInt()); }
        }
    }

    @Test void nativeLegacyMenuRechecksStaffPermissionBeforeOwnerTeleport() throws Exception {
        try (var h = new GraveActionsTest.Harness();
                var builders = mockConstruction(com.bencodez.advancedcore.api.item.ItemBuilder.class, withSettings().defaultAnswer(RETURNS_SELF))) {
            when(h.helper.hasPermission("GraveStonesPlus.AllGraves")).thenReturn(true);
            var commands = new Commands(h); commands.guardNavigation(h.helper,
                    new java.util.concurrent.atomic.AtomicReference<>(new org.bukkit.Location(h.world, 1, 64, 1)));
            var button = h.grave.getGUIItem();
            asyncMenuClick(commands, h, button, h.helper, org.bukkit.event.inventory.ClickType.LEFT);
            when(h.helper.hasPermission("GraveStonesPlus.AllGraves")).thenReturn(false); h.drain();
            verify(h.helper, never()).teleport(any(org.bukkit.Location.class)); assertEquals(0, commands.locationReads);
            verify(h.helper).sendMessage(argThat((String message) -> message.contains("no longer accessible")));
        }
    }

    @Test void actualClaimCommandSkipsRetainedPartialPayoutAndInactiveGravesForOwnerAndRecoverHelper() throws Exception {
        for (boolean helper : List.of(false, true)) try (var h = new GraveActionsTest.Harness()) {
            when(h.config.getGraveClaimDistance()).thenReturn(10);
            when(h.helper.hasPermission("GraveStonesPlus.ClaimGrave")).thenReturn(true);
            if (helper) h.grant(TrustedGraveAccess.Kind.RECOVER);
            // Actual item delivery succeeds, then XP fails, retaining the live grave
            // and durable RESERVED evidence instead of making another payout safe.
            doThrow(new IllegalStateException("injected partial XP payout")).when(h.owner).giveExp(17);
            h.grave.claim(h.owner); h.drain();
            assertTrue(h.plugin.isLiveGrave(h.grave)); assertTrue(h.ledger.status(h.identity()).startsWith("RESERVED:"));
            when(h.owner.isValid()).thenReturn(true); when(h.helper.isValid()).thenReturn(true);
            var commands = new Commands(h);
            var actor = helper ? h.helper : h.owner; assertTrue(h.plugin.canRecover(h.grave, actor));
            var items = new HashMap<Integer, ItemStack>(); items.put(5, h.stored);
            var healthy = new Grave(h.plugin, new com.bencodez.gravestonesplus.storage.GravesConfig(h.owner.getUniqueId(), "Owner",
                    new org.bukkit.Location(h.world, 2, 64, 1), items, 23, "healthy", h.grave.getGravesConfig().getTime() + 1,
                    false, 0, null, null, "PLAYER_HEAD")); h.active.add(healthy);
            var inactive = new Grave(h.plugin, new com.bencodez.gravestonesplus.storage.GravesConfig(h.owner.getUniqueId(), "Owner",
                    new org.bukkit.Location(h.world, 3, 64, 1), new HashMap<>(), 99, "inactive", h.grave.getGravesConfig().getTime() + 2,
                    true, 1, null, null, "PLAYER_HEAD")); h.active.addFirst(inactive);
            if (helper) assertTrue(FeaturePersistenceTest.await(h.access.grantAsync(GraveIdentity.of(healthy.getGravesConfig()),
                    h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER, System.currentTimeMillis() + 60_000)));
            commands.guardNavigation(actor, new java.util.concurrent.atomic.AtomicReference<>(new org.bukkit.Location(h.world, 1, 64, 1)));
            commands.resetReadCounts(); commands.dispatch(actor, "ClaimGrave");
            assertEquals(0, commands.locationReads); assertEquals(0, commands.worldReads);
            h.drain();
            verify(actor).giveExp(23); verify(actor, never()).giveExp(99);
            verify(helper ? h.helperInventory : h.ownerInventory).setItem(5, h.cloned);
            verify(h.ownerInventory, times(1)).setItem(4, h.cloned); verify(h.owner, times(1)).giveExp(17);
            if (helper) { verify(h.helper, never()).giveExp(17); verify(h.helperInventory, never()).setItem(4, h.cloned); }
            assertTrue(healthy.getGravesConfig().isDestroyed()); assertTrue(h.active.contains(h.grave));
            assertTrue(h.ledger.status(h.identity()).startsWith("RESERVED:"));
        }
    }

    @Test void documentedTrustAndRevokeDispatchAndPersistForExactOnlineNamesAndUuids() throws Exception {
        for (TrustedGraveAccess.Kind kind : TrustedGraveAccess.Kind.values()) for (boolean uuid : List.of(false, true)) try (var h = new GraveActionsTest.Harness()) {
            var commands = new Commands(h);
            String helper = uuid ? h.helper.getUniqueId().toString() : "Alex";
            commands.dispatch("Trust", helper, kind.name().toLowerCase(Locale.ROOT), "30m"); h.drain();
            assertTrue(h.access.allows(h.identity(), h.helper.getUniqueId(), kind));
            assertFalse(h.access.allows(h.identity(), h.helper.getUniqueId(), kind == TrustedGraveAccess.Kind.TRACK ? TrustedGraveAccess.Kind.RECOVER : TrustedGraveAccess.Kind.TRACK));
            assertTrue(commands.targetReads > 0);
            verify(h.owner).sendMessage(argThat((String message) -> message.contains("Grant saved")));
            commands.resetReadCounts(); commands.dispatch("RevokeTrust", helper, kind.name().toLowerCase(Locale.ROOT)); h.drain();
            assertFalse(h.access.allows(h.identity(), h.helper.getUniqueId(), kind));
            assertTrue(commands.targetReads > 0);
            verify(h.owner).sendMessage(argThat((String message) -> message.contains("Grant revoked")));
            var tab = TabCompleteHandler.getInstance().getTabCompleteOptions();
            assertEquals(List.of("track", "recover"), tab.get("(GraveAccessKind)"));
            assertTrue(tab.get("(GraveDuration)").contains("30m"));
            assertTrue(tab.get("(GraveHelper)").contains("Alex"));
        }
    }
    @Test void listAndInspectionReadActualPlayerAndItemStateOnlyAfterNativeAsyncAndOwnerDispatch() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var commands = new Commands(h); h.grant(TrustedGraveAccess.Kind.TRACK);
            commands.dispatch("TrustList"); h.drain(); assertTrue(commands.targetReads > 0);
            verify(h.owner).sendMessage(argThat((String message) -> message.contains(h.helper.getUniqueId().toString())));
            commands.resetReadCounts(); commands.dispatch("InspectItem"); h.drain();
            assertEquals(0, commands.targetReads); assertEquals(1, commands.inventoryReads); verify(h.stored).clone();
            var inspection = org.mockito.ArgumentCaptor.forClass(String[].class);
            verify(h.owner).sendMessage(inspection.capture());
            assertTrue(Arrays.stream(inspection.getValue()).anyMatch(message -> message.contains("When grave creation proceeds: ") && message.endsWith("GRAVE_DEFAULT")));
            verify(h.ownerInventory, never()).addItem(any(ItemStack[].class));
            verify(h.ownerInventory, never()).setItem(anyInt(), any()); verify(h.stored, never()).setItemMeta(any());
        }
    }
    private static String[] args(String command, String helper) {
        return switch (command) {
            case "Trust" -> new String[] { command, helper, "recover", "2h" };
            case "RevokeTrust" -> new String[] { command, helper, "recover" };
            default -> new String[] { command };
        };
    }
    @Test void allFourIntroducedHandlersRecheckPermissionOnlineDisableAndReloadBeforeActorReads() throws Exception {
        for (String command : List.of("TrustList", "Trust", "RevokeTrust", "InspectItem")) {
            for (String change : List.of("permission", "offline", "disabled", "reload")) try (var h = new GraveActionsTest.Harness()) {
                var commands = new Commands(h); commands.dispatch(args(command, h.helper.getUniqueId().toString()));
                switch (change) {
                    case "permission" -> when(h.owner.hasPermission(anyString())).thenReturn(false);
                    case "offline" -> when(h.owner.isOnline()).thenReturn(false);
                    case "disabled" -> when(h.plugin.isEnabled()).thenReturn(false);
                    case "reload" -> commands.manager.invalidateAuthorization();
                }
                h.drain(); assertEquals(0, commands.targetReads); assertEquals(0, commands.inventoryReads);
                assertTrue(h.access.list(h.identity()).isEmpty());
                verify(h.owner, never()).sendMessage(any(String.class));
            }
        }
    }
    @Test void actualDispatcherAndActorValidationRejectInvalidArgumentsAndInitialPermissionDenial() throws Exception {
        for (String[] invalid : List.of(
                new String[] { "Trust", "Alex", "admin", "30m" },
                new String[] { "Trust", "Alex", "track", "0" },
                new String[] { "Trust", "Alex", "track", "91d" },
                new String[] { "Trust", "Alex", "track", "bad" },
                new String[] { "Trust", "Missing", "track", "30m" },
                new String[] { "RevokeTrust", "Alex", "admin" })) {
            try (var h = new GraveActionsTest.Harness()) {
                var commands = new Commands(h); commands.dispatch(invalid); h.drain();
                assertEquals(0, commands.targetReads); assertTrue(h.access.list(h.identity()).isEmpty());
                verify(h.owner, atLeastOnce()).sendMessage(any(String.class));
            }
        }
        try (var h = new GraveActionsTest.Harness()) {
            var commands = new Commands(h);
            assertFalse(commands.get("Trust").runCommand(h.owner, new String[] { "Trust", "Alex", "track" }));
            assertFalse(commands.get("RevokeTrust").runCommand(h.owner, new String[] { "RevokeTrust", "Alex" }));
            assertTrue(commands.async.isEmpty()); assertTrue(h.entityWork.isEmpty());
            when(h.owner.hasPermission(anyString())).thenReturn(false);
            assertTrue(commands.get("Trust").runCommand(h.owner, args("Trust", h.helper.getUniqueId().toString())));
            verify(h.owner).sendMessage("Denied"); assertTrue(commands.async.isEmpty()); assertTrue(h.entityWork.isEmpty());
        }
    }
    @Test void durableGrantReplyIsAlsoFencedWhenReloadHappensAfterPublication() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var commands = new Commands(h); commands.dispatch("Trust", h.helper.getUniqueId().toString(), "track", "7d");
            h.entityWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            assertTrue(h.access.allows(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK));
            assertEquals(1, h.entityWork.size()); commands.manager.invalidateAuthorization(); h.drain();
            verify(h.owner, never()).sendMessage(any(String.class));
        }
    }

    @Test void reloadCommandDefersOwnerReplyUntilAsyncReloadCompletes() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var commands = new Commands(h);
            var reload = new CompletableFuture<Void>();
            when(h.plugin.reloadAsync()).thenReturn(reload);
            assertTrue(commands.get("Reload").runCommand(h.owner, new String[] { "Reload" }));
            assertEquals(1, commands.async.size()); commands.async.remove().run();
            verify(h.owner, never()).sendMessage(any(String.class));
            reload.complete(null);
            assertEquals(1, h.entityWork.size(), "completion reply must be dispatched to the owner scheduler");
            h.entityWork.remove().run();
            verify(h.owner).sendMessage("\u00a7cGraveStonesPlus reloaded");
        }
    }

    @Test void reloadCommandReportsAsyncFailureOnlyOnOwnerScheduler() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var commands = new Commands(h);
            var reload = new CompletableFuture<Void>();
            when(h.plugin.reloadAsync()).thenReturn(reload);
            assertTrue(commands.get("Reload").runCommand(h.owner, new String[] { "Reload" }));
            assertEquals(1, commands.async.size()); commands.async.remove().run();
            reload.completeExceptionally(new IllegalStateException("read failed"));
            verify(h.owner, never()).sendMessage(any(String.class));
            assertEquals(1, h.entityWork.size());
            h.entityWork.remove().run();
            verify(h.owner).sendMessage("\u00a7cGraveStonesPlus reload failed; inspect the server log");
        }
    }
}
