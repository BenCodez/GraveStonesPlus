package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.concurrent.*;
import com.bencodez.gravestonesplus.events.*;
import com.bencodez.gravestonesplus.storage.*;
import org.bukkit.Location;
import org.bukkit.entity.ExperienceOrb;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Exercises actual removal, durable access/claim stores, recreation and restoration. */
class RemovalRecoveryRegressionTest {
    @Test void failedRemovalRevocationCannotSurviveHistoricalRestartAndRecreation() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.grant(TrustedGraveAccess.Kind.TRACK); h.grant(TrustedGraveAccess.Kind.RECOVER);
            h.accessDisk.fail = true;
            h.grave.removeGrave(GraveRemoveReason.MANUAL_REMOVE);
            GraveActionsTest.drainGround(h);
            assertTrue(h.grave.getGravesConfig().isDestroyed());
            assertFalse(h.access.allows(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER));
            assertEquals(2, h.accessDisk.values.size(), "failed publication retained the original disk grants");
            assertFalse(FeaturePersistenceTest.await(h.grave.recreateAsync(null, false)));
            assertTrue(h.regionWork.isEmpty());

            h.accessDisk.fail = false;
            var restarted = new TrustedGraveAccessStore(new DurableFeatureState(h.io, h.accessDisk, 4096), System::currentTimeMillis);
            assertTrue(FeaturePersistenceTest.await(restarted.loaded()));
            when(h.plugin.getTrustedGraveAccessStore()).thenReturn(restarted);
            assertTrue(restarted.allows(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER));
            var backend = mock(GraveStorageManager.class);
            when(backend.loadGraves()).thenReturn(List.of());
            when(backend.loadBrokenGraves()).thenReturn(List.of(h.grave.getGravesConfig()));
            when(h.config.getBrokenGraveTimeLimit()).thenReturn("7d");
            when(h.plugin.getFeatureIO()).thenReturn(h.io);
            var cleanup = mock(GraveMarkerCleanup.class);
            when(cleanup.retain(any())).thenReturn(CompletableFuture.completedFuture(true));
            when(h.plugin.getMarkerCleanup()).thenReturn(cleanup);
            var restored = new ArrayList<Grave>(); var published = new CountDownLatch(1);
            doAnswer(call -> { restored.add(call.getArgument(0)); published.countDown(); return null; })
                    .when(h.plugin).restoreBrokenRecord(any());
            var restoration = new GraveRestoration(h.plugin, backend);
            GraveActionsTest.retryOnOwner(h, restoration); assertTrue(published.await(2, TimeUnit.SECONDS)); h.drain();
            Grave historical = restored.getFirst();
            assertTrue(historical.getGravesConfig().isDestroyed());
            assertFalse(h.plugin.canRecover(historical, h.helper));

            // A second failure at the actual recreation boundary must leave history unchanged.
            h.accessDisk.fail = true;
            assertFalse(FeaturePersistenceTest.await(historical.recreateAsync(null, false)));
            assertTrue(historical.getGravesConfig().isDestroyed());
            assertEquals(2, h.accessDisk.values.size()); assertTrue(h.regionWork.isEmpty());
            h.accessDisk.fail = false;
            var retryStore = new TrustedGraveAccessStore(new DurableFeatureState(h.io, h.accessDisk, 4096), System::currentTimeMillis);
            assertTrue(FeaturePersistenceTest.await(retryStore.loaded()));
            when(h.plugin.getTrustedGraveAccessStore()).thenReturn(retryStore);
            GraveActionsTest.field(h.plugin, "trustedGraveAccessStore", retryStore);
            h.accessDisk.entered = new CountDownLatch(1); h.accessDisk.release = new CountDownLatch(1);
            var recreation = historical.recreateAsync(null, false);
            try {
                assertTrue(h.accessDisk.entered.await(2, TimeUnit.SECONDS));
                assertFalse(recreation.isDone()); assertTrue(historical.getGravesConfig().isDestroyed());
                assertTrue(h.regionWork.isEmpty(), "no marker/state callback before durable helper removal");
                assertFalse(retryStore.allows(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK));
                assertFalse(retryStore.allows(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER));
            } finally { h.accessDisk.release.countDown(); }
            h.drain(); h.regionWork.remove().run();
            assertTrue(FeaturePersistenceTest.await(recreation)); assertTrue(h.accessDisk.values.isEmpty());
            h.active.add(historical);
            assertTrue(h.plugin.canRecover(historical, h.owner));
            assertFalse(h.plugin.canRecover(historical, h.helper));
            assertFalse(h.plugin.canTrack(historical, h.helper));
            assertFalse(retryStore.allows(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER));
            var finalRestart = new TrustedGraveAccessStore(new DurableFeatureState(h.io, h.accessDisk, 4096), System::currentTimeMillis);
            assertTrue(FeaturePersistenceTest.await(finalRestart.loaded())); assertTrue(finalRestart.list(h.identity()).isEmpty());
            assertEquals(17, historical.getGravesConfig().getExp());
            verify(h.owner, never()).giveExp(anyInt()); verify(h.helper, never()).giveExp(anyInt());
            restoration.retire();
        }
    }

    @Test void recreationAttemptOnLiveGraveDoesNotRevokeLegitimateHelpers() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.grant(TrustedGraveAccess.Kind.TRACK); h.grant(TrustedGraveAccess.Kind.RECOVER);
            assertFalse(FeaturePersistenceTest.await(h.grave.recreateAsync(null, false)));
            assertTrue(h.plugin.canTrack(h.grave, h.helper)); assertTrue(h.plugin.canRecover(h.grave, h.helper));
            assertEquals(2, h.accessDisk.values.size()); assertTrue(h.regionWork.isEmpty());
        }
    }

    @Test void rejectedRemovalRevocationAndRecreationRetryDoNotBlockGameplayOwners() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var accessIO = new FeatureIO(1)) {
            h.grant(TrustedGraveAccess.Kind.TRACK); h.grant(TrustedGraveAccess.Kind.RECOVER);
            var access = new TrustedGraveAccessStore(new DurableFeatureState(accessIO, h.accessDisk, 4096), System::currentTimeMillis);
            assertTrue(FeaturePersistenceTest.await(access.loaded())); when(h.plugin.getTrustedGraveAccessStore()).thenReturn(access);
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var busy = accessIO.submit(() -> {
                entered.countDown(); try { if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test worker not released"); }
                catch (InterruptedException failure) { throw new IllegalStateException(failure); } return true;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); var queued = accessIO.submit(() -> true);
            try {
                h.grave.removeGrave(GraveRemoveReason.MANUAL_REMOVE); h.regionWork.remove().run();
                assertTrue(h.grave.getGravesConfig().isDestroyed()); assertEquals(2, h.accessDisk.values.size());
                assertFalse(FeaturePersistenceTest.await(h.grave.recreateAsync(null, false)));
                assertTrue(h.regionWork.isEmpty()); assertTrue(h.entityWork.isEmpty());
                assertFalse(access.allows(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER));
            } finally { release.countDown(); }
            FeaturePersistenceTest.await(busy); FeaturePersistenceTest.await(queued);
            var recreation = h.grave.recreateAsync(null, false);
            // Ledger and access intentionally use different serial workers in this rejection fixture.
            h.drain(); FeaturePersistenceTest.await(accessIO.barrier()); h.drain();
            h.regionWork.remove().run(); assertTrue(FeaturePersistenceTest.await(recreation));
            assertTrue(h.accessDisk.values.isEmpty()); assertEquals(17, h.grave.getGravesConfig().getExp());
        }
    }

    @Test void restoredQueuedLimitInitializesFutureAndPastDeadlinesBeforeAnyPayout() throws Exception {
        for (boolean expired : List.of(false, true)) try (var h = new GraveActionsTest.Harness()) {
            long death = System.currentTimeMillis() - (expired ? 120_000L : 0L);
            var original = h.grave.getGravesConfig();
            var record = new GravesConfig(original.getUuid(), original.getPlayerName(), original.getLocation(),
                    new HashMap<>(original.getItems()), original.getExp(), original.getDeathMessage(), death,
                    false, 0L, null, null, "PLAYER_HEAD");
            String identity = GraveIdentity.of(record);
            assertTrue(FeaturePersistenceTest.await(h.ledger.queueLimit(identity, record.getUuid(),
                    GraveClaimLedger.evidence(GraveStorageManager.snapshot(record)))));
            var restartLedger = new GraveClaimLedger(new DurableFeatureState(h.io, h.claimDisk, 8192));
            assertTrue(FeaturePersistenceTest.await(restartLedger.loaded())); when(h.plugin.getGraveClaimLedger()).thenReturn(restartLedger);
            h.active.clear(); when(h.config.getGraveTimeLimit()).thenReturn("1m");
            var timer = mock(ScheduledExecutorService.class); var timerTask = mock(ScheduledFuture.class);
            when(timer.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenReturn(timerTask);
            when(h.plugin.getTimer()).thenReturn(timer); when(h.plugin.getFeatureIO()).thenReturn(h.io);
            var backend = mock(GraveStorageManager.class);
            when(backend.loadGraves()).thenReturn(List.of(record)); when(backend.loadBrokenGraves()).thenReturn(List.of());
            doAnswer(call -> { h.active.add(call.getArgument(0)); return null; }).when(h.plugin).restoreActiveRecord(any());
            var restoration = new GraveRestoration(h.plugin, backend); when(h.plugin.getGraveRestoration()).thenReturn(restoration);
            GraveActionsTest.retryOnOwner(h, restoration); GraveActionsTest.drainGround(h);
            var admitted = ArgumentCaptor.forClass(Grave.class); verify(h.plugin).restoreActiveRecord(admitted.capture());
            Grave restored = admitted.getValue(); assertEquals(death + 60_000L, restored.getExpiryDeadline());
            verify(h.world, never()).dropItem(any(), any()); verify(h.world, never()).getChunkAt(anyInt(), anyInt());
            if (expired) {
                assertTrue(restored.isRemove()); assertTrue(record.isDestroyed()); assertTrue(h.active.isEmpty());
                assertNull(restartLedger.status(identity));
                GraveActionsTest.groundWorld(h); restored.resumeLimitPayout(); GraveActionsTest.drainGround(h);
                verify(h.world, never()).dropItem(any(), any()); verify(h.world, never()).spawn(any(Location.class), eq(ExperienceOrb.class));
            } else {
                assertTrue(h.plugin.isLiveGrave(restored)); assertTrue(restored.isLimitPayoutPending());
                verify(timer).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
                var orb = GraveActionsTest.groundWorld(h); restoration.onChunk(h.world.getUID(), 0, 0);
                GraveActionsTest.drainGround(h);
                assertEquals(org.bukkit.Material.AIR, h.world.getBlockAt(record.getLocation()).getType());
                assertTrue(h.world.getBlockAt(record.getLocation()).getMetadata("Grave").isEmpty());
                verify(h.world, times(1)).dropItem(any(), eq(h.cloned)); verify(orb).setExperience(17);
                assertEquals(GraveClaimLedger.Restoration.PAID, FeaturePersistenceTest.await(restartLedger.restoration(identity)));
            }
            restoration.retire();
        }
    }

    @Test void cancelledExpiryKeepsQueuedContentsAndPermitsTheExistingNoDeadlinePolicy() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var restoration = GraveActionsTest.limitRestoration(h); var orb = GraveActionsTest.groundWorld(h);
            assertTrue(FeaturePersistenceTest.await(h.ledger.queueLimit(h.identity(), h.owner.getUniqueId(),
                    GraveClaimLedger.evidence(GraveStorageManager.snapshot(h.grave.getGravesConfig())))));
            h.grave.markLimitPayoutPending();
            var deadline = Grave.class.getDeclaredField("expiryDeadline"); deadline.setAccessible(true);
            deadline.setLong(h.grave, System.currentTimeMillis() - 1L);
            doAnswer(call -> {
                if (call.getArgument(0) instanceof GraveRemoveEvent event && event.getReason() == GraveRemoveReason.EXPIRED)
                    event.setCancelled(true);
                return null;
            }).when(h.events).callEvent(any());
            h.grave.resumeLimitPayout();
            assertFalse(h.grave.isRemove()); assertEquals(0L, h.grave.getExpiryDeadline());
            assertTrue(h.ledger.status(h.identity()).startsWith("QUEUED_LIMIT:"));
            verify(h.world, never()).dropItem(any(), any());
            h.grave.resumeLimitPayout(); GraveActionsTest.drainGround(h);
            verify(h.world, times(1)).dropItem(any(), eq(h.cloned)); verify(orb).setExperience(17);
            assertTrue(h.ledger.status(h.identity()).startsWith("PAID:")); restoration.retire();
        }
    }

    @Test void expiryDuringDiskReservationOrRemovalEventCannotDropQueuedContents() throws Exception {
        for (boolean expireInEvent : List.of(false, true)) try (var h = new GraveActionsTest.Harness()) {
            // Cancellation resumes on the storage worker, outside Bukkit ownership.
            var workerBukkit = FeaturePersistenceTest.await(h.io.submit(() -> {
                var context = mockStatic(org.bukkit.Bukkit.class);
                context.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(false);
                return context;
            }));
            try {
            var restoration = GraveActionsTest.limitRestoration(h); GraveActionsTest.groundWorld(h);
            assertTrue(FeaturePersistenceTest.await(h.ledger.queueLimit(h.identity(), h.owner.getUniqueId(),
                    GraveClaimLedger.evidence(GraveStorageManager.snapshot(h.grave.getGravesConfig())))));
            h.grave.markLimitPayoutPending();
            var deadline = Grave.class.getDeclaredField("expiryDeadline"); deadline.setAccessible(true);
            if (expireInEvent) doAnswer(call -> {
                if (call.getArgument(0) instanceof GraveRemoveEvent event && event.getReason() == GraveRemoveReason.LIMIT_REACHED)
                    deadline.setLong(h.grave, System.currentTimeMillis() - 1L);
                return null;
            }).when(h.events).callEvent(any());
            h.grave.resumeLimitPayout(); h.drain();
            assertTrue(h.ledger.status(h.identity()).startsWith("RESERVED:"));
            if (!expireInEvent) deadline.setLong(h.grave, System.currentTimeMillis() - 1L);
            h.regionWork.remove().run(); GraveActionsTest.drainGround(h);
            verify(h.world, never()).dropItem(any(), any()); verify(h.world, never()).spawn(any(Location.class), eq(ExperienceOrb.class));
            assertEquals(org.bukkit.Material.AIR, h.world.getBlockAt(h.grave.getGravesConfig().getLocation()).getType());
            assertTrue(h.world.getBlockAt(h.grave.getGravesConfig().getLocation()).getMetadata("Grave").isEmpty());
            verify(h.owner, never()).giveExp(anyInt()); assertTrue(h.grave.getGravesConfig().isDestroyed());
            assertNull(h.ledger.status(h.identity())); assertEquals(17, h.grave.getGravesConfig().getExp());
            restoration.retire();
            } finally {
                FeaturePersistenceTest.await(h.io.submit(() -> { workerBukkit.close(); return null; }));
            }
        }
    }
}
