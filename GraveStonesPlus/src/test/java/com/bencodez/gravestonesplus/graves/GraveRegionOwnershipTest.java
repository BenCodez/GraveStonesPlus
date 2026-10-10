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
                var restored = grave.recreateAsync(h.helper, true); h.drain();
                assertEquals(1, h.regionWork.size()); assertFalse(restored.isDone());
                graveOwner.set(true); h.regionWork.remove().run(); graveOwner.set(false);
                assertEquals(1, h.entityWork.size()); assertTrue(grave.getGravesConfig().isDestroyed());
                verify(h.world, never()).getBlockAt(any(Location.class));
                actorOwner.set(true); h.entityWork.remove().run(); actorOwner.set(false);
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
            var restored = h.grave.recreateAsync(h.helper, true); h.drain();
            h.regionWork.remove().run(); h.entityWork.remove().run();
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
