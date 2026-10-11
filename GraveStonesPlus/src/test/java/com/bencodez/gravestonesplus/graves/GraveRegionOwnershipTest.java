package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

/** Exercises the real marker scheduler, including actors in a different region. */
class GraveRegionOwnershipTest {
    @Test void remoteClaimCallbacksBelongToTheGraveRegion() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            when(h.folia.isFolia()).thenReturn(true);
            var graveOwner = new AtomicBoolean();
            doAnswer(call -> {
                assertTrue(graveOwner.get(), "claim callbacks must run on the grave owner, not the distant player owner");
                if (call.getArgument(0) instanceof com.bencodez.gravestonesplus.events.GraveClaimEvent event)
                    event.setCancelled(true);
                return null;
            }).when(h.events).callEvent(any());
            h.grave.claim(h.owner);
            assertEquals(1, h.entityWork.size());
            h.entityWork.remove().run();
            verify(h.events, never()).callEvent(any());
            assertEquals(1, h.regionWork.size());
            graveOwner.set(true); h.regionWork.remove().run(); graveOwner.set(false);
            verify(h.events).callEvent(any(com.bencodez.gravestonesplus.events.GraveClaimEvent.class));
            verify(h.ownerInventory, never()).setItem(anyInt(), any());
            verify(h.owner, never()).giveExp(anyInt());
        }
    }
    @Test void bukkitClaimCallbacksRemainSynchronousOnPrimaryThread() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.bukkit.when(org.bukkit.Bukkit::isPrimaryThread).thenReturn(true);
            doAnswer(call -> { if (call.getArgument(0) instanceof com.bencodez.gravestonesplus.events.GraveClaimEvent event)
                event.setCancelled(true); return null;
            }).when(h.events).callEvent(any());
            h.grave.claim(h.owner);
            verify(h.events).callEvent(any(com.bencodez.gravestonesplus.events.GraveClaimEvent.class));
            assertTrue(h.entityWork.isEmpty()); assertTrue(h.regionWork.isEmpty());
            assertFalse(h.ledger.isBlocked(h.identity()));
        }
    }
    @Test void foliaClaimCapturesCallbackChangesAndAdmitsOnlyOnePayout() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            when(h.folia.isFolia()).thenReturn(true);
            var actor = new AtomicBoolean(); var marker = new AtomicBoolean();
            when(h.owner.isOnline()).thenAnswer(call -> { assertTrue(actor.get()); assertFalse(marker.get()); return true; });
            when(h.stored.clone()).thenAnswer(call -> { assertTrue(marker.get()); return h.cloned; });
            doAnswer(call -> {
                assertTrue(marker.get()); assertFalse(actor.get());
                if (call.getArgument(0) instanceof com.bencodez.gravestonesplus.events.GraveClaimEvent) {
                    var exp = com.bencodez.gravestonesplus.storage.GravesConfig.class.getDeclaredField("exp");
                    exp.setAccessible(true); exp.setInt(h.grave.getGravesConfig(), 31);
                }
                return null;
            }).when(h.events).callEvent(any());
            h.grave.claim(h.owner); h.grave.claim(h.owner);
            actor.set(true); while (!h.entityWork.isEmpty()) h.entityWork.remove().run(); actor.set(false);
            assertEquals(2, h.regionWork.size());
            marker.set(true); while (!h.regionWork.isEmpty()) h.regionWork.remove().run(); marker.set(false);
            verify(h.events, times(1)).callEvent(any(com.bencodez.gravestonesplus.events.GraveClaimEvent.class));
            assertEquals(1, h.entityWork.size());
            actor.set(true); h.entityWork.remove().run(); actor.set(false);
            FeaturePersistenceTest.await(h.io.barrier());
            actor.set(true); while (!h.entityWork.isEmpty()) h.entityWork.remove().run(); actor.set(false);
            FeaturePersistenceTest.await(h.io.barrier());
            verify(h.owner, times(1)).giveExp(31);
            verify(h.ownerInventory, times(1)).setItem(4, h.cloned);
        }
    }
    @Test void rejectedClaimRegionAdmissionLeavesTheGraveClaimable() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            when(h.folia.isFolia()).thenReturn(true);
            doThrow(new java.util.concurrent.RejectedExecutionException("fixture retired region"))
                .when(h.scheduler).runTask(eq(h.plugin), any(Runnable.class), any(Location.class));
            h.grave.claim(h.owner); h.entityWork.remove().run();
            verify(h.events, never()).callEvent(any()); assertFalse(h.ledger.isBlocked(h.identity()));
            var inProgress = Grave.class.getDeclaredField("claimInProgress"); inProgress.setAccessible(true);
            assertFalse(inProgress.getBoolean(h.grave)); verify(h.owner, never()).giveExp(anyInt());
        }
    }
    @Test void retiredActorAfterClaimCallbacksReleasesUnreservedAdmission() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            when(h.folia.isFolia()).thenReturn(true);
            doAnswer(call -> { ((Runnable) call.getArgument(2)).run();
                return java.util.concurrent.CompletableFuture.completedFuture(
                    com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS);
            }).when(h.platform).runAtEntityWithFallback(any(org.bukkit.entity.Entity.class), any(), any(Runnable.class));
            h.grave.claim(h.owner); h.entityWork.remove().run(); h.regionWork.remove().run();
            assertFalse(h.ledger.isBlocked(h.identity()));
            var inProgress = Grave.class.getDeclaredField("claimInProgress"); inProgress.setAccessible(true);
            assertFalse(inProgress.getBoolean(h.grave)); verify(h.owner, never()).giveExp(anyInt());
        }
    }
    @Test void foliaChunkLoadUsesRegionTicketWithoutGlobalForceLoadedState() throws Exception {
        for (boolean deferred : List.of(false, true)) {
            try (var h = new GraveActionsTest.Harness()) {
                when(h.folia.isFolia()).thenReturn(true);
                var owned = new AtomicBoolean();
                var block = mock(Block.class); var chunk = mock(Chunk.class);
                when(h.world.getBlockAt(any(Location.class))).thenAnswer(call -> {
                    assertTrue(owned.get()); return block;
                });
                when(block.getChunk()).thenReturn(chunk);
                when(chunk.load(false)).thenAnswer(call -> { assertTrue(owned.get()); return true; });
                if (!deferred) owned.set(true);
                h.grave.loadChunk(deferred);
                if (deferred) {
                    verify(chunk, never()).load(false);
                    assertEquals(1, h.regionWork.size());
                    owned.set(true); h.regionWork.remove().run();
                }
                verify(chunk).load(false);
                verify(chunk, never()).isForceLoaded(); verify(chunk, never()).setForceLoaded(anyBoolean());
                verify(h.scheduler).runTaskLater(eq(h.plugin), any(Runnable.class), eq(120L),
                        eq(h.grave.getGravesConfig().getLocation()));
                assertTrue(h.ownerWork.isEmpty());
            }
        }
    }

    @Test void disabledFoliaChunkLoadDoesNotTouchWorldOrRetainTicket() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            when(h.folia.isFolia()).thenReturn(true);
            h.grave.loadChunk(true); when(h.plugin.isEnabled()).thenReturn(false);
            h.regionWork.remove().run();
            verify(h.world, never()).getBlockAt(any(Location.class));
            verify(h.scheduler, never()).runTaskLater(eq(h.plugin), any(Runnable.class), anyLong(), any(Location.class));
        }
    }

    @Test void bukkitChunkLoadPreservesExistingForceLoadAndRelease() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var block = mock(Block.class); var chunk = mock(Chunk.class);
            when(h.world.getBlockAt(any(Location.class))).thenReturn(block); when(block.getChunk()).thenReturn(chunk);
            h.grave.loadChunk(false);
            verify(chunk).isForceLoaded(); verify(chunk).setForceLoaded(true); verify(chunk).load(false);
            var release = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(h.scheduler).runTaskLater(eq(h.plugin), release.capture(), eq(120L), any(Location.class));
            release.getValue().run(); verify(chunk).setForceLoaded(false);
        }
    }

    @Test void remoteActorAuthorizesOnEntityOwnerAndMarkerUsesSavedLocation() throws Exception {
        for (String type : List.of("CHEST", "PLAYER_HEAD", "DISPLAY_ENTITY")) {
            try (var h = new GraveActionsTest.Harness()) {
                when(h.folia.isFolia()).thenReturn(true);
                var actorOwner = new AtomicBoolean();
                var graveOwner = new AtomicBoolean();
                when(h.helper.hasPermission(anyString())).thenAnswer(call -> {
                    assertTrue(actorOwner.get(), "authorization must use the actor owner");
                    assertFalse(graveOwner.get(), "actor may be in a different region");
                    return call.getArgument(0).equals("GraveStonesPlus.Admin");
                });
                var grave = spy(h.grave);
                // Metadata and display implementations have their own tests. Keep the
                // real createGrave/loadChunk methods to prove world access is scheduled.
                doAnswer(call -> { assertTrue(graveOwner.get()); return null; }).when(grave).loadBlockMeta(any());
                doAnswer(call -> { assertTrue(graveOwner.get()); return null; }).when(grave).createHologram();
                var block = mock(Block.class); var chunk = mock(Chunk.class);
                when(h.world.getBlockAt(any(Location.class))).thenAnswer(call -> {
                    assertTrue(graveOwner.get(), "block lookup belongs to the grave region");
                    assertFalse(actorOwner.get()); return block;
                });
                when(block.getChunk()).thenReturn(chunk);
                when(chunk.isForceLoaded()).thenReturn(true);
                doAnswer(call -> { assertTrue(graveOwner.get()); assertFalse(actorOwner.get()); return null; })
                        .when(block).setType(any(Material.class));
                var display = mock(com.bencodez.gravestonesplus.graves.GraveDisplayEntityHandle.class);
                when(h.plugin.getGraveDisplayEntityHandler()).thenReturn(display);
                when(display.createDisplay(grave)).thenAnswer(call -> {
                    assertTrue(graveOwner.get()); assertFalse(actorOwner.get()); return null;
                });
                h.active.clear(); grave.getGravesConfig().setDestroyed(true);
                grave.getGravesConfig().setGraveDisplayType(type);
                var restored = grave.recreateAsync(h.helper, true);
                FeaturePersistenceTest.await(h.io.barrier()); FeaturePersistenceTest.await(h.io.barrier());
                assertTrue(h.regionWork.isEmpty()); assertFalse(restored.isDone());
                assertEquals(1, h.entityWork.size()); assertTrue(grave.getGravesConfig().isDestroyed());
                verify(h.world, never()).getBlockAt(any(Location.class));
                actorOwner.set(true); h.entityWork.remove().run(); actorOwner.set(false);
                assertEquals(1, h.regionWork.size()); assertFalse(restored.isDone());
                assertTrue(grave.getGravesConfig().isDestroyed());
                graveOwner.set(true); h.regionWork.remove().run(); graveOwner.set(false);
                assertTrue(FeaturePersistenceTest.await(restored));
                assertFalse(grave.getGravesConfig().isDestroyed());
                verify(h.world, never()).getBlockAt(any(Location.class));
                assertEquals(2, h.regionWork.size(), "marker and hologram each dispatch to the grave");
                graveOwner.set(true);
                while (!h.regionWork.isEmpty()) h.regionWork.remove().run();
                graveOwner.set(false);
                Material material = type.equals("CHEST") ? Material.CHEST : type.equals("PLAYER_HEAD") ? Material.PLAYER_HEAD : Material.BARRIER;
                verify(block).setType(material); verify(grave).loadBlockMeta(block); verify(grave).createHologram();
                Location savedLocation = grave.getGravesConfig().getLocation();
                verify(h.scheduler, times(2)).runTask(eq(h.plugin), any(Runnable.class), eq(savedLocation));
            }
        }
    }

    @Test void remoteActorPermissionRevocationRejectsBeforeMarkerDispatch() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            when(h.folia.isFolia()).thenReturn(true);
            h.grave.getGravesConfig().setDestroyed(true);
            var restored = h.grave.recreateAsync(h.helper, true);
            FeaturePersistenceTest.await(h.io.barrier()); FeaturePersistenceTest.await(h.io.barrier());
            assertTrue(h.regionWork.isEmpty()); h.entityWork.remove().run();
            assertFalse(FeaturePersistenceTest.await(restored));
            assertTrue(h.grave.getGravesConfig().isDestroyed());
            assertTrue(h.regionWork.isEmpty());
            verify(h.world, never()).getBlockAt(any(Location.class));
        }
    }

    @Test void locationOnlyRecreationKeepsLocationScheduledMarkerPath() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            when(h.folia.isFolia()).thenReturn(true);
            var grave = spy(h.grave); doNothing().when(grave).createGrave(); doNothing().when(grave).createHologram();
            grave.getGravesConfig().setDestroyed(true);
            var restored = grave.recreateAsync(null, true); h.drain();
            h.regionWork.remove().run();
            assertTrue(FeaturePersistenceTest.await(restored)); verify(grave).createGrave();
            assertTrue(h.entityWork.isEmpty());
        }
    }
}
