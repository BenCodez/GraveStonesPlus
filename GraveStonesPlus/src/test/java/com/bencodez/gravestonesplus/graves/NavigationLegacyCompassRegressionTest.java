package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

/** Regression coverage for legacy compasses during asynchronous restoration admission. */
class NavigationLegacyCompassRegressionTest {
    @Test void rejectedPlayerRefreshDoesNotInterruptRemovalOrOtherPlayers() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.grant(TrustedGraveAccess.Kind.TRACK);
            assertTrue(h.navigation.select(h.owner, h.grave));
            h.bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(h.owner, h.helper));
            doThrow(new java.util.concurrent.RejectedExecutionException("retired player"))
                    .when(h.scheduler).runTask(eq(h.plugin), any(Runnable.class), eq(h.owner));

            h.grave.removeGrave(com.bencodez.gravestonesplus.events.GraveRemoveReason.MANUAL_REMOVE);
            assertDoesNotThrow(() -> h.regionWork.remove().run());
            h.drain();

            assertTrue(h.grave.isRemove());
            assertTrue(h.grave.getGravesConfig().isDestroyed());
            assertFalse(h.active.contains(h.grave));
            assertTrue(h.access.list(h.identity()).isEmpty(), "removal still revokes helper grants");
            assertNull(h.navigation.target(h.owner));
            verify(h.scheduler, never()).runTask(eq(h.plugin), any(Runnable.class), any(org.bukkit.entity.Entity.class));
            verify(h.plugin).removeGrave(h.grave);
        }
    }

    @Test void invalidationDoesNotFanOutOwnerTasks() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            assertTrue(h.navigation.select(h.owner, h.grave));
            h.bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(h.owner, h.helper));

            assertDoesNotThrow(() -> h.navigation.invalidate(h.identity()));

            assertNull(h.navigation.target(h.owner));
            verify(h.scheduler, never()).runTask(eq(h.plugin), any(Runnable.class), any(org.bukkit.entity.Entity.class));
        }
    }

    @Test void repeatedInvalidationDoesNotIncreaseRefreshFanout() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.navigation.invalidate(h.identity());
            h.navigation.invalidate(h.identity());
            verify(h.scheduler, never()).runTask(eq(h.plugin), any(Runnable.class), any(org.bukkit.entity.Entity.class));
        }
    }

    @Test void boundedLoginRefreshClearsRemovedCompassEvenWhenReminderStoreFailed() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            assertTrue(h.navigation.select(h.owner, h.grave));
            Compass compass = compass(h, h.grave.getGravesConfig().getTime(), h.grave.getGravesConfig().getLocation());
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] { compass.item });
            var disk = new FeaturePersistenceTest.Disk();
            var state = new DurableFeatureState(h.io, disk, 8192);
            var reminders = new GraveReminderStore(state);
            assertTrue(FeaturePersistenceTest.await(reminders.loaded()));
            disk.fail = true;
            assertFalse(FeaturePersistenceTest.await(state.update(next -> { next.put("fixture", "value"); return true; })));
            assertFalse(reminders.isReady());
            when(h.plugin.getGraveReminderStore()).thenReturn(reminders);
            var listener = new com.bencodez.gravestonesplus.listeners.GraveReminderListener(h.plugin);
            listener.start();
            h.active.clear();
            h.navigation.invalidate(h.identity());
            verify(compass.item, never()).setItemMeta(any());
            listener.onJoin(new org.bukkit.event.player.PlayerJoinEvent(h.owner, ""));
            h.drain();
            verify(compass.meta).setLodestone(null);
            verify(compass.item).setItemMeta(compass.meta);
            verify(h.owner, never()).sendMessage(anyString());
            listener.stop();
        }
    }

    @Test void exactPendingLegacyCompassSurvivesAfterBackendLoadingCompletes() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.active.clear();
            long time = h.grave.getGravesConfig().getTime();
            var restoration = mock(GraveRestoration.class);
            when(restoration.isLoading()).thenReturn(false);
            when(restoration.hasPending(h.identity())).thenReturn(true);
            when(h.plugin.getGraveRestoration()).thenReturn(restoration);
            Compass compass = compass(h, time, new Location(h.world, 1, 64, 1));
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] { compass.item });

            h.navigation.refresh(h.owner);

            verify(compass.data, never()).remove(any());
            verify(compass.item, never()).setItemMeta(any());
        }
    }

    @Test void unrelatedLegacyCompassIsClearedEvenWhileAnotherRecordIsPending() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.active.clear();
            var restoration = mock(GraveRestoration.class);
            when(restoration.isLoading()).thenReturn(false);
            when(restoration.hasPending(h.identity())).thenReturn(false);
            when(h.plugin.getGraveRestoration()).thenReturn(restoration);
            Compass compass = compass(h, h.grave.getGravesConfig().getTime() + 1, new Location(h.world, 1, 64, 1));
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] { compass.item });

            h.navigation.refresh(h.owner);

            verify(compass.data, times(2)).remove(any());
            verify(compass.item).setItemMeta(compass.meta);
        }
    }

    @Test void legacyCompassIsClearedAfterItsPendingRecordResolvesWithoutAdmission() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.active.clear();
            long time = h.grave.getGravesConfig().getTime();
            var restoration = mock(GraveRestoration.class);
            when(restoration.isLoading()).thenReturn(false);
            when(restoration.hasPending(h.identity())).thenReturn(true, false);
            when(h.plugin.getGraveRestoration()).thenReturn(restoration);
            Compass compass = compass(h, time, new Location(h.world, 1, 64, 1));
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] { compass.item });

            h.navigation.refresh(h.owner);
            verify(compass.data, never()).remove(any());
            reset(compass.data, compass.item);
            when(compass.item.getType()).thenReturn(Material.COMPASS);
            when(compass.item.getItemMeta()).thenReturn(compass.meta);
            when(compass.meta.getPersistentDataContainer()).thenReturn(compass.data);
            when(compass.data.get(any(), eq(PersistentDataType.STRING))).thenReturn(null);
            when(compass.data.has(any(), eq(PersistentDataType.LONG))).thenReturn(true);
            when(compass.data.get(any(), eq(PersistentDataType.LONG))).thenReturn(time);
            when(compass.meta.getLodestone()).thenReturn(new Location(h.world, 1, 64, 1));

            h.navigation.refresh(h.owner);

            verify(compass.data, times(2)).remove(any());
            verify(compass.item).setItemMeta(compass.meta);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void unavailableFeatureStateNeverErasesCompassOrSelection(boolean claimFailure) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            assertTrue(h.navigation.select(h.owner, h.grave));
            Compass compass = compass(h, h.grave.getGravesConfig().getTime(), h.grave.getGravesConfig().getLocation());
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] {compass.item});
            var disk = new FeaturePersistenceTest.Disk();
            var state = new DurableFeatureState(h.io, disk, 8192);
            assertTrue(FeaturePersistenceTest.await(state.loaded()));
            if (claimFailure) when(h.plugin.getGraveClaimLedger()).thenReturn(new GraveClaimLedger(state));
            else when(h.plugin.getTrustedGraveAccessStore()).thenReturn(new TrustedGraveAccessStore(state, System::currentTimeMillis));
            disk.fail = true;
            assertFalse(FeaturePersistenceTest.await(state.update(next -> { next.put("fixture", "value"); return true; })));
            assertTrue(FeaturePersistenceTest.await(state.loaded()), "initial load succeeded before uncertain publication");
            assertNull(h.navigation.target(h.owner)); h.navigation.refresh(h.owner);
            verify(compass.data, never()).remove(any()); verify(compass.item, never()).setItemMeta(any());
            // Restoring an available store lets the same selected target return without re-selection.
            if (claimFailure) when(h.plugin.getGraveClaimLedger()).thenReturn(h.ledger);
            else when(h.plugin.getTrustedGraveAccessStore()).thenReturn(h.access);
            assertSame(h.grave, h.navigation.target(h.owner));
        }
    }

    private static Compass compass(GraveActionsTest.Harness h, long time, Location location) {
        ItemStack item = mock(ItemStack.class);
        CompassMeta meta = mock(CompassMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.COMPASS);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(), eq(PersistentDataType.STRING))).thenReturn(null);
        when(data.has(any(), eq(PersistentDataType.LONG))).thenReturn(true);
        when(data.get(any(), eq(PersistentDataType.LONG))).thenReturn(time);
        when(meta.getLodestone()).thenReturn(location);
        return new Compass(item, meta, data);
    }

    private record Compass(ItemStack item, CompassMeta meta, PersistentDataContainer data) { }
}
