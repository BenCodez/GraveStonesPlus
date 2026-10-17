package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.util.*;

import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.advancedcore.api.item.FullInventoryHandler;
import com.bencodez.gravestonesplus.commands.CommandLoader;
import com.bencodez.gravestonesplus.listeners.GraveReminderListener;
import com.bencodez.gravestonesplus.storage.GravesConfig;
import com.bencodez.simpleapi.time.ParsedDuration;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class GraveReviewRegressionTest {
    @Test void claimRestoresAvailableOriginalSlotsBeforeInsertingDisplacedStacks() throws Exception {
        try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            ItemStack occupied = mock(ItemStack.class), originalZero = mock(ItemStack.class), originalOne = mock(ItemStack.class);
            org.bukkit.Material type = h.stored.getType();
            for (ItemStack stack : List.of(occupied, originalZero, originalOne)) {
                when(stack.getType()).thenReturn(type);
                when(stack.clone()).thenReturn(stack);
            }
            when(originalZero.serialize()).thenReturn(Map.of("type", "DIAMOND", "amount", 2));
            when(originalOne.serialize()).thenReturn(Map.of("type", "GOLD_INGOT", "amount", 3));
            ItemStack[] slots = new ItemStack[41]; slots[0] = occupied;
            when(h.ownerInventory.getItem(anyInt())).thenAnswer(call -> slots[call.getArgument(0, Integer.class)]);
            doAnswer(call -> { slots[call.getArgument(0, Integer.class)] = call.getArgument(1); return null; })
                    .when(h.ownerInventory).setItem(anyInt(), any(ItemStack.class));
            when(h.ownerInventory.addItem(any(ItemStack.class))).thenAnswer(call -> {
                ItemStack stack = call.getArgument(0);
                for (int slot = 0; slot < 36; slot++) if (slots[slot] == null) {
                    slots[slot] = stack;
                    return new HashMap<Integer, ItemStack>();
                }
                return new HashMap<>(Map.of(0, stack));
            });
            Grave grave = new Grave(h.plugin, new GravesConfig(h.owner.getUniqueId(), "Owner", h.owner.getLocation(),
                    new HashMap<>(Map.of(0, originalZero, 1, originalOne)), 17, "death", System.currentTimeMillis(),
                    false, 0, null, null, "PLAYER_HEAD"));
            h.active.clear(); h.active.add(grave);

            grave.claim(h.owner); h.drain();

            assertSame(occupied, slots[0]);
            assertSame(originalOne, slots[1], "An available original slot must be reserved before displaced insertion");
            assertSame(originalZero, slots[2]);
            verify(h.ownerInventory).setItem(1, originalOne);
            verify(h.ownerInventory).addItem(originalZero);
            verify(h.ownerInventory, never()).addItem(originalOne);
            verify(h.world, never()).dropItem(any(), any());
            verify(h.owner).giveExp(17);
            assertTrue(grave.getGravesConfig().isDestroyed());
        }
    }

    @Test void fullInventoryClaimRetainsOverflowWhenAdvancedCoreDropPolicyIsDisabled() throws Exception {
        assertOverflowPolicy(false);
    }

    @Test void fullInventoryClaimDropsOverflowWhenAdvancedCoreDropPolicyIsEnabled() throws Exception {
        assertOverflowPolicy(true);
    }

    private void assertOverflowPolicy(boolean drop) throws Exception {
        try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            AdvancedCoreConfigOptions options = mock(AdvancedCoreConfigOptions.class);
            UUID ownerId = h.owner.getUniqueId(); h.bukkit.when(() -> org.bukkit.Bukkit.getPlayer(ownerId)).thenReturn(h.owner);
            when(options.isDropOnFullInv()).thenReturn(drop); when(options.getFormatInvFull()).thenReturn("");
            when(h.plugin.getOptions()).thenReturn(options);
            var serverData = mock(com.bencodez.advancedcore.data.ServerData.class);
            when(serverData.getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
            when(h.plugin.getServerDataFile()).thenReturn(serverData);
            FullInventoryHandler handler = spy(new FullInventoryHandler(h.plugin));
            try {
            when(h.plugin.getFullInventoryHandler()).thenReturn(handler);
            ItemStack occupied = mock(ItemStack.class); org.bukkit.Material type = h.stored.getType();
            when(occupied.getType()).thenReturn(type);
            when(h.ownerInventory.getItem(4)).thenReturn(occupied);
            when(h.ownerInventory.addItem(h.cloned)).thenReturn(new HashMap<>(Map.of(0, h.cloned)));
            when(h.world.dropItem(h.owner.getLocation(), h.cloned)).thenReturn(mock(org.bukkit.entity.Item.class));
            var delivery = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Void>>();
            doAnswer(call -> {
                var stage = (java.util.concurrent.CompletionStage<Void>) call.callRealMethod();
                delivery.set(stage.toCompletableFuture()); return stage;
            }).when(handler).giveItemAsync(h.owner, h.cloned);

            var completionScheduled = new java.util.concurrent.CountDownLatch(1);
            doAnswer(call -> {
                java.util.function.Consumer<com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask> work = call.getArgument(1);
                var completed = new java.util.concurrent.CompletableFuture<com.bencodez.simpleapi.folialib.enums.EntityTaskResult>();
                h.entityWork.add(() -> { work.accept(null); completed.complete(com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS); });
                if (delivery.get() != null) completionScheduled.countDown();
                return completed;
            }).when(h.platform).runAtEntityWithFallback(any(org.bukkit.entity.Entity.class), any(), any(Runnable.class));
            h.grave.claim(h.owner); h.drain();
            assertNotNull(delivery.get()); FeaturePersistenceTest.await(delivery.get());
            assertTrue(completionScheduled.await(3, java.util.concurrent.TimeUnit.SECONDS), "overflow completion must schedule the recipient callback"); h.drain();

            verify(handler).giveItemAsync(h.owner, h.cloned);
            verify(h.world, times(drop ? 1 : 0)).dropItem(h.owner.getLocation(), h.cloned);
            if (drop) assertFalse(handler.getItems().containsKey(h.owner.getUniqueId()));
            else assertEquals(List.of(h.cloned), handler.getItems().get(h.owner.getUniqueId()));
            verify(h.owner).giveExp(17);
            assertTrue(h.grave.getGravesConfig().isDestroyed());
            assertTrue(h.ledger.status(h.identity()).startsWith("PAID:"));
            } finally { handler.shutdown(); }
        }
    }

    @Test void claimCommandHonorsOtherPlayersBreakTimeWhileTrustedRecoveryRemainsImmediate() throws Exception {
        try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            when(h.plugin.getOptions()).thenReturn(mock(AdvancedCoreConfigOptions.class));
            ArrayList<CommandHandler> commands = new ArrayList<>(); when(h.plugin.getCommands()).thenReturn(commands);
            new CommandLoader(h.plugin).loadCommands();
            CommandHandler claim = commands.stream().filter(command -> command.getArgs()[0].equalsIgnoreCase("ClaimGrave"))
                    .findFirst().orElseThrow();
            when(h.helper.hasPermission("GraveStonesPlus.ClaimGrave")).thenReturn(true);
            when(h.helper.hasPermission("GraveStonesPlus.BreakOtherGraves")).thenReturn(true);
            when(h.config.getGraveClaimDistance()).thenReturn(10);
            when(h.config.isBreakOtherGravesEnabled()).thenReturn(true);
            when(h.config.isBreakOtherGravesRequirePermission()).thenReturn(true);
            when(h.config.isBreakOtherGravesRequireBreakTime()).thenReturn(true);
            when(h.config.getBreakOtherGravesTimeBeforeBreakable()).thenReturn(ParsedDuration.parse("0", java.util.concurrent.TimeUnit.MINUTES));
            assertTrue(claim.hasPerm(h.helper)); assertTrue(h.grave.canNonOwnerClaim());
            assertFalse(h.plugin.canRecover(h.grave, h.helper));

            claim.execute(h.helper, new String[] { "claimgrave" }); h.drain();

            verify(h.helper, never()).giveExp(anyInt()); verify(h.helperInventory, never()).setItem(anyInt(), any());
            assertFalse(h.ledger.isBlocked(h.identity())); assertFalse(h.grave.getGravesConfig().isDestroyed());
            h.grant(TrustedGraveAccess.Kind.RECOVER);
            claim.execute(h.helper, new String[] { "claimgrave" }); h.drain();
            verify(h.helper).giveExp(17); verify(h.helperInventory).setItem(4, h.cloned);
            assertTrue(h.grave.getGravesConfig().isDestroyed());
            assertTrue(h.ledger.status(h.identity()).startsWith("PAID:"));
        }
    }

    @Test void fortyGravesDeliverAllEightyWarningsAcrossCappedPassesWithoutDuplicates() throws Exception {
        try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            DurableFeatureState state = new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192);
            GraveReminderStore store = new GraveReminderStore(state); FeaturePersistenceTest.await(store.loaded());
            when(h.plugin.getGraveReminderStore()).thenReturn(store);
            when(h.config.isBreakOtherGravesEnabled()).thenReturn(true);
            when(h.config.getProtectionReminderMillis()).thenReturn(120_000L);
            when(h.config.getExpiryReminderMillis()).thenReturn(120_000L);
            long created = System.currentTimeMillis(), expiry = created + 90_000;
            Field expiryDeadline = Grave.class.getDeclaredField("expiryDeadline"); expiryDeadline.setAccessible(true);
            Set<String> expected = new HashSet<>(); h.active.clear();
            for (int index = 0; index < 40; index++) {
                Grave grave = new Grave(h.plugin, new GravesConfig(h.owner.getUniqueId(), "Owner", new Location(h.world, index, 64, 1),
                        new HashMap<>(), 0, "death", created, false, 0, null, null, "PLAYER_HEAD"));
                expiryDeadline.setLong(grave, expiry); h.active.add(grave);
                String prefix = h.owner.getUniqueId() + "/" + GraveIdentity.of(grave.getGravesConfig()) + "/";
                expected.add(prefix + "expiry/" + expiry);
                expected.add(prefix + "protection/" + (created + 60_000));
            }
            GraveReminderListener listener = new GraveReminderListener(h.plugin); listener.start();
            try {
                listener.checkPlayer(h.owner); h.drain();
                assertEquals(64, state.snapshot().size(), "The first delivery must obey the 64-warning cap");
                verify(h.owner).sendMessage(argThat((String message) -> message.contains("+61 deadlines")));

                listener.checkPlayer(h.owner); h.drain();
                assertEquals(expected, state.snapshot().keySet(), "Already-delivered warnings must not crowd out the remaining 16");
                verify(h.owner).sendMessage(argThat((String message) -> message.contains("+13 deadlines")));

                listener.checkPlayer(h.owner); h.drain();
                assertEquals(expected, state.snapshot().keySet());
                verify(h.owner, times(2)).sendMessage(any(String.class));
            } finally { listener.stop(); }
        }
    }
}
