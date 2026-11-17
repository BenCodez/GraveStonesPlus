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
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void reservationPreservesAuthorizedCompassAndSelectionUntilItsOutcome(boolean legacy) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.grant(TrustedGraveAccess.Kind.TRACK);
            Compass compass = compass(h, h.grave.getGravesConfig().getTime(), new Location(h.world, 1, 64, 1));
            if (!legacy) when(compass.data.get(any(), eq(PersistentDataType.STRING))).thenReturn(h.identity());
            var viewer = legacy ? h.owner : h.helper;
            when((legacy ? h.ownerInventory : h.helperInventory).getContents()).thenReturn(new ItemStack[] {compass.item});
            assertTrue(h.navigation.select(viewer, h.grave));
            // Exercise both process-local admission and persisted RESERVED state.
            var reservation = h.ledger.reserve(h.identity(), h.owner.getUniqueId(), "synthetic evidence");
            assertNull(h.navigation.target(viewer)); h.navigation.refresh(viewer);
            verify(compass.data, never()).remove(any()); verify(compass.item, never()).setItemMeta(any());
            assertTrue(FeaturePersistenceTest.await(reservation));
            h.navigation.refresh(viewer); verify(compass.data, never()).remove(any());
            assertTrue(FeaturePersistenceTest.await(h.ledger.cancel(h.identity())));
            assertSame(h.grave, h.navigation.target(viewer));
            h.navigation.refresh(viewer); verify(compass.meta).setLodestone(h.grave.getGravesConfig().getLocation());
            // Durable reservation must never override removal or helper revocation.
            assertTrue(FeaturePersistenceTest.await(h.ledger.reserve(h.identity(), h.owner.getUniqueId())));
            if (legacy) h.active.clear();
            else assertTrue(FeaturePersistenceTest.await(h.access.revokeAsync(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK)));
            h.navigation.refresh(viewer); verify(compass.data, times(2)).remove(any()); assertNull(h.navigation.target(viewer));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cancelledExpirationRestoresSelectedTrackingWithoutRetagging(boolean legacy) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.grant(TrustedGraveAccess.Kind.TRACK);
            Compass compass = compass(h, h.grave.getGravesConfig().getTime(), h.grave.getGravesConfig().getLocation());
            if (!legacy) when(compass.data.get(any(), eq(PersistentDataType.STRING))).thenReturn(h.identity());
            var viewer = legacy ? h.owner : h.helper;
            when((legacy ? h.ownerInventory : h.helperInventory).getContents()).thenReturn(new ItemStack[] {compass.item});
            assertTrue(h.navigation.select(viewer, h.grave));
            expire(h.grave);
            assertNull(h.navigation.target(viewer), "elapsed deadline must not expose a usable target");
            h.navigation.refresh(viewer);
            verify(compass.data, never()).remove(any()); verify(compass.item, never()).setItemMeta(any());
            doAnswer(call -> {
                if (call.getArgument(0) instanceof com.bencodez.gravestonesplus.events.GraveRemoveEvent event
                        && event.getReason() == com.bencodez.gravestonesplus.events.GraveRemoveReason.EXPIRED)
                    event.setCancelled(true);
                return null;
            }).when(h.events).callEvent(any());
            h.bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(true);
            try { h.grave.removeGrave(com.bencodez.gravestonesplus.events.GraveRemoveReason.EXPIRED); }
            finally { h.bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(false); }
            assertEquals(0, h.grave.getExpiryDeadline()); assertFalse(h.grave.isRemove());
            assertSame(h.grave, h.navigation.target(viewer), "cancelled expiry must preserve the selected identity");
            h.navigation.refresh(viewer);
            verify(compass.meta).setLodestone(h.grave.getGravesConfig().getLocation());
            verify(compass.data, never()).remove(any());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void committedExpirationClearsPreviouslyPreservedCompass(boolean legacy) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.grant(TrustedGraveAccess.Kind.TRACK);
            Compass compass = compass(h, h.grave.getGravesConfig().getTime(), h.grave.getGravesConfig().getLocation());
            if (!legacy) when(compass.data.get(any(), eq(PersistentDataType.STRING))).thenReturn(h.identity());
            var viewer = legacy ? h.owner : h.helper;
            when((legacy ? h.ownerInventory : h.helperInventory).getContents()).thenReturn(new ItemStack[] {compass.item});
            assertTrue(h.navigation.select(viewer, h.grave)); expire(h.grave);
            h.navigation.refresh(viewer); verify(compass.data, never()).remove(any());
            h.bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(true);
            try { h.grave.removeGrave(com.bencodez.gravestonesplus.events.GraveRemoveReason.EXPIRED); }
            finally { h.bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(false); }
            assertTrue(h.grave.isRemove()); assertTrue(h.grave.getGravesConfig().isDestroyed());
            h.navigation.refresh(viewer); verify(compass.data, times(2)).remove(any());
            assertNull(h.navigation.target(viewer));
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"revoked", "paid", "destroyed", "removed"})
    void elapsedDeadlineCannotPreservePermanentlyUnauthorizedTargets(String terminal) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.grant(TrustedGraveAccess.Kind.TRACK);
            Compass compass = compass(h, h.grave.getGravesConfig().getTime(), h.grave.getGravesConfig().getLocation());
            when(compass.data.get(any(), eq(PersistentDataType.STRING))).thenReturn(h.identity());
            when(h.helperInventory.getContents()).thenReturn(new ItemStack[] {compass.item});
            assertTrue(h.navigation.select(h.helper, h.grave)); expire(h.grave);
            switch (terminal) {
                case "revoked" -> assertTrue(FeaturePersistenceTest.await(h.access.revokeAsync(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK)));
                case "paid" -> {
                    assertTrue(FeaturePersistenceTest.await(h.ledger.reserve(h.identity(), h.owner.getUniqueId())));
                    assertTrue(FeaturePersistenceTest.await(h.ledger.paid(h.identity(), h.owner.getUniqueId())));
                }
                case "destroyed" -> h.grave.getGravesConfig().setDestroyed(true);
                case "removed" -> h.active.clear();
                default -> throw new AssertionError(terminal);
            }
            assertNull(h.navigation.target(h.helper)); h.navigation.refresh(h.helper);
            verify(compass.data, times(2)).remove(any()); verify(compass.meta).setLodestone(null);
        }
    }
    @Test void cancellationDuringTrackabilityCheckCannotEraseTheRestoredSelection() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            Compass compass = compass(h, h.grave.getGravesConfig().getTime(), h.grave.getGravesConfig().getLocation());
            when(compass.data.get(any(), eq(PersistentDataType.STRING))).thenReturn(h.identity());
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] {compass.item});
            assertTrue(h.navigation.select(h.owner, h.grave)); expire(h.grave);
            when(h.plugin.canTrack(h.grave, h.owner)).thenAnswer(call -> {
                if (h.grave.getExpiryDeadline() > 0) {
                    var deadline = Grave.class.getDeclaredField("expiryDeadline"); deadline.setAccessible(true);
                    deadline.setLong(h.grave, 0); // Cancellation wins after the first expired-state observation.
                    return false;
                }
                return call.callRealMethod();
            });
            h.navigation.refresh(h.owner);
            verify(compass.data, never()).remove(any());
            assertSame(h.grave, h.navigation.target(h.owner));
        }
    }

    private static void expire(Grave grave) throws Exception {
        var deadline = Grave.class.getDeclaredField("expiryDeadline"); deadline.setAccessible(true);
        deadline.setLong(grave, System.currentTimeMillis() - 1);
    }

    @Test void paidOutcomeInvalidatesThePreviouslyReservedCompass() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            Compass compass = compass(h, h.grave.getGravesConfig().getTime(), new Location(h.world, 1, 64, 1));
            when(compass.data.get(any(), eq(PersistentDataType.STRING))).thenReturn(h.identity());
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] {compass.item});
            assertTrue(h.navigation.select(h.owner, h.grave));
            assertTrue(FeaturePersistenceTest.await(h.ledger.reserve(h.identity(), h.owner.getUniqueId())));
            h.navigation.refresh(h.owner); verify(compass.data, never()).remove(any());
            assertTrue(FeaturePersistenceTest.await(h.ledger.paid(h.identity(), h.owner.getUniqueId())));
            h.navigation.refresh(h.owner); verify(compass.data, times(2)).remove(any()); assertNull(h.navigation.target(h.owner));
        }
    }
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
