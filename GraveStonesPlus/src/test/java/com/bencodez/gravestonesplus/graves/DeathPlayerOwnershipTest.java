package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.gravestonesplus.listeners.PlayerDeathListener;

/** Production death/respawn entry points, with independently controlled region/player queues. */
class DeathPlayerOwnershipTest {
    private static void capture(GraveActionsTest.Harness h, PlayerDeathListener listener, Player player,
            PlayerInventory inventory, ItemStack item) {
        when(h.folia.isFolia()).thenReturn(true);
        when(h.config.getDisabledWorlds()).thenReturn(new ArrayList<>());
        when(h.config.getGraveLimit()).thenReturn(10);
        when(h.config.getKeepItemsWithLore()).thenReturn("keep");
        when(h.config.getFormatDeath()).thenReturn("Grave at %x%");
        when(h.config.getGraveDisplayTypeEnum()).thenReturn(GraveDisplayType.CHEST);
        when(h.world.getMinHeight()).thenReturn(-64); when(h.world.getMaxHeight()).thenReturn(320);
        Block block = mock(Block.class); when(block.isEmpty()).thenReturn(true);
        when(h.world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(h.world.getBlockAt(any(Location.class))).thenReturn(block);
        when(player.getName()).thenReturn("Owner"); when(player.getDisplayName()).thenReturn("Owner");
        when(player.hasPermission("GraveStonesPlus.AllowGrave")).thenReturn(true);
        when(inventory.getContents()).thenReturn(new ItemStack[] { item });
        when(inventory.getArmorContents()).thenReturn(new ItemStack[4]);
        when(inventory.getItem(4)).thenReturn(item);
        ItemMeta meta = mock(ItemMeta.class);
        when(meta.hasLore()).thenReturn(true); when(meta.getLore()).thenReturn(List.of("keep"));
        when(item.hasItemMeta()).thenReturn(true); when(item.getItemMeta()).thenReturn(meta);
        doAnswer(call -> { h.regionWork.add(call.getArgument(1)); return null; })
            .when(h.scheduler).runTaskLater(eq(h.plugin), any(Runnable.class), eq(2L), any(Location.class));
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getEntity()).thenReturn(player); when(event.getDrops()).thenReturn(new ArrayList<>());
        listener.onPlayerDeath(event);
        verify(event).setDroppedExp(0);
        // Respawn replaces the original inventory contents.
        when(inventory.getItem(4)).thenReturn(null);
    }
    private static void respawn(PlayerDeathListener listener, Player player) {
        PlayerRespawnEvent event = mock(PlayerRespawnEvent.class); when(event.getPlayer()).thenReturn(player);
        listener.onRespawn(event);
    }
    @Test void keptItemsUseMovingPlayerOwnerAndRepeatedCallbacksDeliverOnlyOnce() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            respawn(listener, h.owner); respawn(listener, h.owner);
            assertTrue(h.ownerWork.isEmpty(), "No inventory access through the global scheduler");
            assertEquals(2, h.entityWork.size());
            verify(h.ownerInventory, never()).setItem(anyInt(), any());
            h.drain();
            verify(h.ownerInventory, times(1)).setItem(4, h.cloned);
            respawn(listener, h.owner); assertTrue(h.entityWork.isEmpty());
        }
    }
    @Test void rejectedRespawnAdmissionKeepsItemsForTheNextRespawn() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            doThrow(new IllegalStateException("retired player scheduler")).doAnswer(call -> {
                h.entityWork.add(call.getArgument(1)); return null;
            }).when(h.scheduler).runTask(eq(h.plugin), any(Runnable.class), eq(h.owner));
            assertDoesNotThrow(() -> respawn(listener, h.owner));
            respawn(listener, h.owner); h.drain();
            verify(h.ownerInventory, times(1)).setItem(eq(4), any(ItemStack.class));
        }
    }
    @ParameterizedTest @ValueSource(booleans = { false, true })
    void offlineOrDisabledCallbackRetainsSnapshotAndAnotherPlayerCannotConsumeIt(boolean disabled) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            respawn(listener, h.helper); assertTrue(h.entityWork.isEmpty());
            respawn(listener, h.owner);
            if (disabled) when(h.plugin.isEnabled()).thenReturn(false);
            else when(h.owner.isOnline()).thenReturn(false);
            h.drain();
            verify(h.ownerInventory, never()).setItem(anyInt(), any());
            when(h.owner.isOnline()).thenReturn(true); when(h.plugin.isEnabled()).thenReturn(true);
            respawn(listener, h.owner); h.drain();
            verify(h.ownerInventory, times(1)).setItem(eq(4), any(ItemStack.class));
            verify(h.helperInventory, never()).setItem(anyInt(), any());
        }
    }
    @Test void staleRespawnCallbackCannotConsumeTheNextDeathEvenWithIdenticalItems() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            respawn(listener, h.owner);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            when(h.ownerInventory.getItem(4)).thenReturn(null, h.cloned);
            h.entityWork.remove().run();
            verify(h.ownerInventory, never()).setItem(anyInt(), any());
            respawn(listener, h.owner); h.drain();
            verify(h.ownerInventory, times(1)).setItem(4, h.cloned);
            verify(h.ownerInventory, times(1)).addItem(h.cloned);
            respawn(listener, h.owner); assertTrue(h.entityWork.isEmpty());
        }
    }
    @Test void successiveDifferentItemsSharingASlotBothSurviveAndFullInventoryDropsOverflow() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            respawn(listener, h.owner);
            var second = mock(ItemStack.class); var secondClone = mock(ItemStack.class);
            var material = h.stored.getType();
            when(second.getType()).thenReturn(material); when(second.clone()).thenReturn(secondClone);
            when(secondClone.getType()).thenReturn(material);
            capture(h, listener, h.owner, h.ownerInventory, second);
            when(h.ownerInventory.getItem(4)).thenReturn(null, h.cloned);
            when(h.ownerInventory.addItem(secondClone)).thenReturn(new HashMap<>(Map.of(0, secondClone)));
            h.entityWork.remove().run(); verify(h.ownerInventory, never()).setItem(anyInt(), any());
            respawn(listener, h.owner); respawn(listener, h.owner); h.drain();
            verify(h.ownerInventory, times(1)).setItem(4, h.cloned);
            verify(h.ownerInventory, times(1)).addItem(secondClone);
            verify(h.world, times(1)).dropItemNaturally(any(Location.class), eq(secondClone));
            respawn(listener, h.owner); assertTrue(h.entityWork.isEmpty());
        }
    }
    @ParameterizedTest @ValueSource(booleans = { false, true })
    void snapshotBoundLeavesNewDeathDropsAndExperienceUntouchedAndRetainsEarlierEntitlements(boolean dropOldContents) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            for (int i = 0; i < 8; i++) capture(h, listener, h.owner, h.ownerInventory, h.stored);
            when(h.ownerInventory.getItem(4)).thenReturn(h.stored);
            Grave oldest = mock(Grave.class);
            when(h.plugin.numberOfGraves(h.owner.getUniqueId())).thenReturn(1);
            when(h.config.getGraveLimit()).thenReturn(1);
            when(h.config.isDropItemsOnGraveRemoval()).thenReturn(dropOldContents);
            when(h.plugin.getOldestGrave(h.owner.getUniqueId())).thenReturn(oldest);
            var event = mock(PlayerDeathEvent.class); when(event.getEntity()).thenReturn(h.owner);
            var drops = new ArrayList<>(List.of(h.stored)); when(event.getDrops()).thenReturn(drops);
            when(event.getDroppedExp()).thenReturn(7);
            listener.onPlayerDeath(event);
            assertEquals(List.of(h.stored), drops); verify(event, never()).setDroppedExp(anyInt());
            verify(oldest, never()).removeGrave(any());
            verify(oldest, never()).dropAndRemoveForLimit(any());
            assertEquals(8, h.regionWork.size(), "Rejected ninth death must not queue a replacement");
            when(h.ownerInventory.getItem(4)).thenReturn(null, h.cloned);
            respawn(listener, h.owner); respawn(listener, h.owner); h.drain();
            verify(h.ownerInventory, times(1)).setItem(4, h.cloned);
            verify(h.ownerInventory, times(7)).addItem(h.cloned);
            respawn(listener, h.owner); assertTrue(h.entityWork.isEmpty());
        }
    }
    @ParameterizedTest @ValueSource(booleans = { false, true })
    void missingReplacementLocationPreservesOldGraveAndNewDeathDrops(boolean dropOldContents) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            when(h.ownerInventory.getItem(4)).thenReturn(h.stored);
            Grave oldest = mock(Grave.class);
            when(h.plugin.numberOfGraves(h.owner.getUniqueId())).thenReturn(1);
            when(h.config.getGraveLimit()).thenReturn(1);
            when(h.config.isDropItemsOnGraveRemoval()).thenReturn(dropOldContents);
            when(h.plugin.getOldestGrave(h.owner.getUniqueId())).thenReturn(oldest);
            Block occupied = mock(Block.class);
            when(occupied.isEmpty()).thenReturn(false);
            when(occupied.getType()).thenReturn(Material.STONE);
            when(h.world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(occupied);
            when(h.world.getBlockAt(any(Location.class))).thenReturn(occupied);
            var event = mock(PlayerDeathEvent.class);
            when(event.getEntity()).thenReturn(h.owner);
            var drops = new ArrayList<>(List.of(h.stored));
            when(event.getDrops()).thenReturn(drops);
            when(event.getDroppedExp()).thenReturn(7);
            listener.onPlayerDeath(event);
            assertEquals(List.of(h.stored), drops);
            verify(event, never()).setDroppedExp(anyInt());
            verify(oldest, never()).removeGrave(any());
            verify(oldest, never()).dropAndRemoveForLimit(any());
            assertEquals(1, h.regionWork.size(), "Failed location preflight must not queue a replacement");
        }
    }
    @ParameterizedTest @ValueSource(booleans = { false, true })
    void acceptedReplacementStillUsesConfiguredLimitRemoval(boolean dropOldContents) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            when(h.ownerInventory.getItem(4)).thenReturn(h.stored);
            Grave oldest = mock(Grave.class);
            when(h.plugin.numberOfGraves(h.owner.getUniqueId())).thenReturn(1);
            when(h.config.getGraveLimit()).thenReturn(1);
            when(h.config.isDropItemsOnGraveRemoval()).thenReturn(dropOldContents);
            when(h.plugin.getOldestGrave(h.owner.getUniqueId())).thenReturn(oldest);
            var event = mock(PlayerDeathEvent.class);
            when(event.getEntity()).thenReturn(h.owner);
            var drops = new ArrayList<>(List.of(h.stored));
            when(event.getDrops()).thenReturn(drops);
            listener.onPlayerDeath(event);
            assertTrue(drops.isEmpty()); verify(event).setDroppedExp(0);
            if (dropOldContents) {
                verify(oldest, times(1)).dropAndRemoveForLimit(h.owner);
                verify(oldest, never()).removeGrave(any());
            } else {
                verify(oldest, times(1)).removeGrave(com.bencodez.gravestonesplus.events.GraveRemoveReason.LIMIT_REACHED);
                verify(oldest, never()).dropAndRemoveForLimit(any());
            }
            assertEquals(2, h.regionWork.size());
        }
    }
    @ParameterizedTest @ValueSource(booleans = { false, true })
    void failedLimitPreflightRollsBackOnlyNewKeptBatchAndCannotPayBeforeDropsClear(boolean dropOldContents) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            when(h.ownerInventory.getItem(4)).thenReturn(h.stored);
            Grave oldest = mock(Grave.class);
            when(h.plugin.numberOfGraves(h.owner.getUniqueId())).thenReturn(1);
            when(h.config.getGraveLimit()).thenReturn(1);
            when(h.config.isDropItemsOnGraveRemoval()).thenReturn(dropOldContents);
            when(h.plugin.getOldestGrave(h.owner.getUniqueId())).thenReturn(oldest);
            org.mockito.stubbing.Answer<Void> fail = call -> {
                respawn(listener, h.owner);
                h.entityWork.remove().run();
                verify(h.ownerInventory, never()).setItem(anyInt(), any());
                throw new IllegalStateException("limit removal rejected");
            };
            if (dropOldContents) doAnswer(fail).when(oldest).dropAndRemoveForLimit(any());
            else doAnswer(fail).when(oldest).removeGrave(any());
            var event = mock(PlayerDeathEvent.class);
            when(event.getEntity()).thenReturn(h.owner);
            var drops = new ArrayList<>(List.of(h.stored));
            when(event.getDrops()).thenReturn(drops);
            assertThrows(IllegalStateException.class, () -> listener.onPlayerDeath(event));
            assertEquals(List.of(h.stored), drops); verify(event, never()).setDroppedExp(anyInt());
            assertEquals(1, h.regionWork.size());
            when(h.ownerInventory.getItem(4)).thenReturn(null);
            respawn(listener, h.owner); h.drain();
            verify(h.ownerInventory, times(1)).setItem(4, h.cloned);
            verify(h.ownerInventory, never()).addItem(any(ItemStack.class));
        }
    }
    @Test void failedPreflightDoesNotDiscardASuccessorKeptBatch() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            when(h.ownerInventory.getItem(4)).thenReturn(h.stored);
            Grave oldest = mock(Grave.class);
            when(h.plugin.numberOfGraves(h.owner.getUniqueId())).thenReturn(1);
            when(h.config.getGraveLimit()).thenReturn(1);
            when(h.plugin.getOldestGrave(h.owner.getUniqueId())).thenReturn(oldest);
            var successor = mock(ItemStack.class); var successorClone = mock(ItemStack.class);
            var material = h.stored.getType();
            when(successor.getType()).thenReturn(material); when(successor.clone()).thenReturn(successorClone);
            when(successorClone.getType()).thenReturn(material);
            doAnswer(call -> {
                capture(h, listener, h.owner, h.ownerInventory, successor);
                throw new IllegalStateException("predecessor removal failed");
            }).when(oldest).removeGrave(any());
            var event = mock(PlayerDeathEvent.class);
            when(event.getEntity()).thenReturn(h.owner);
            var drops = new ArrayList<>(List.of(h.stored)); when(event.getDrops()).thenReturn(drops);
            assertThrows(IllegalStateException.class, () -> listener.onPlayerDeath(event));
            assertEquals(List.of(h.stored), drops); verify(event, never()).setDroppedExp(anyInt());
            when(h.ownerInventory.getItem(4)).thenReturn(null, h.cloned);
            respawn(listener, h.owner); h.drain();
            verify(h.ownerInventory, times(1)).setItem(4, h.cloned);
            verify(h.ownerInventory, times(1)).addItem(successorClone);
            verify(h.ownerInventory, never()).addItem(h.cloned);
        }
    }
    @Test void deferredGraveReceivesDetachedCapturedItems() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            when(h.ownerInventory.getItem(4)).thenReturn(h.stored);
            when(h.config.getKeepItemsWithLore()).thenReturn("");
            when(h.stored.hasItemMeta()).thenReturn(false);
            var event = mock(PlayerDeathEvent.class);
            when(event.getEntity()).thenReturn(h.owner); when(event.getDrops()).thenReturn(new ArrayList<>());
            listener.onPlayerDeath(event);
            var records = new ArrayList<com.bencodez.gravestonesplus.storage.GravesConfig>();
            try (var graves = mockConstruction(Grave.class, (grave, context) ->
                    records.add((com.bencodez.gravestonesplus.storage.GravesConfig) context.arguments().get(1)))) {
                h.regionWork.remove().run(); // first kept-only death
                h.regionWork.remove().run(); // second grave-bound death
                assertSame(h.cloned, records.get(1).getItems().get(4));
                assertNotSame(h.stored, records.get(1).getItems().get(4));
            }
        }
    }
    @Test void simultaneousPendingPlayersRestoreTheirOwnSnapshotsOnly() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            capture(h, listener, h.helper, h.helperInventory, h.cloned);
            respawn(listener, h.helper); respawn(listener, h.owner);
            assertTrue(h.ownerWork.isEmpty()); assertEquals(2, h.entityWork.size());
            h.entityWork.remove().run();
            verify(h.helperInventory).setItem(4, h.cloned);
            verify(h.ownerInventory, never()).setItem(anyInt(), any());
            h.entityWork.remove().run(); verify(h.ownerInventory).setItem(4, h.cloned);
        }
    }
    @ParameterizedTest @ValueSource(strings = { "keepInventory", "permission", "noKeptItems" })
    void subsequentDeathFencesOlderRespawnWithoutDiscardingUndeliveredItems(String change) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            respawn(listener, h.owner);
            PlayerDeathEvent secondDeath = mock(PlayerDeathEvent.class);
            when(secondDeath.getEntity()).thenReturn(h.owner);
            when(secondDeath.getDrops()).thenReturn(new ArrayList<>());
            if (change.equals("keepInventory")) when(secondDeath.getKeepInventory()).thenReturn(true);
            else if (change.equals("permission")) when(h.owner.hasPermission("GraveStonesPlus.AllowGrave")).thenReturn(false);
            // No item is present in the inventory after the first native death/respawn.
            listener.onPlayerDeath(secondDeath);
            h.entityWork.remove().run();
            verify(h.ownerInventory, never()).setItem(anyInt(), any());
            respawn(listener, h.owner); h.drain();
            verify(h.ownerInventory, times(1)).setItem(4, h.cloned);
        }
    }
    @Test void deadPlayerCallbackKeepsThePendingSnapshotForALivingRespawn() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            respawn(listener, h.owner); when(h.owner.isDead()).thenReturn(true); h.drain();
            verify(h.ownerInventory, never()).setItem(anyInt(), any());
            when(h.owner.isDead()).thenReturn(false); respawn(listener, h.owner); h.drain();
            verify(h.ownerInventory).setItem(4, h.cloned);
        }
    }
    @Test void graveLocationTaskDoesNotReadOrMessageThePlayerUntilItsOwnerRuns() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var graves = mockConstruction(Grave.class)) {
            var listener = new PlayerDeathListener(h.plugin);
            capture(h, listener, h.owner, h.ownerInventory, h.stored);
            clearInvocations(h.owner);
            h.regionWork.remove().run();
            verify(h.owner, never()).getUniqueId(); verify(h.owner, never()).getName();
            verify(h.owner, never()).sendMessage(anyString());
            assertEquals(1, graves.constructed().size()); assertEquals(1, h.entityWork.size());
            h.drain(); verify(h.owner).sendMessage("Grave at 1");
        }
    }
}
