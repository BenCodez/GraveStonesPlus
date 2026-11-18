package com.bencodez.gravestonesplus.tests.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.persistence.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.gravestonesplus.graves.*;
import com.bencodez.gravestonesplus.listeners.*;
import com.bencodez.gravestonesplus.storage.*;
import com.bencodez.gravestonesplus.tests.storage.SqlJournalHarness;

class PendingRecreationProtectionTest {
    @TempDir Path directory;
    static final class Marker {
        final Block block=mock(Block.class);final Chunk chunk=mock(Chunk.class);final PersistentDataContainer data=mock(PersistentDataContainer.class);
        final Map<NamespacedKey,String> values=new HashMap<>();
        Marker(ClaimHarness h,Material material) {
            var location=h.grave.getGravesConfig().getLocation();
            when(block.getX()).thenReturn(location.getBlockX());when(block.getY()).thenReturn(location.getBlockY());when(block.getZ()).thenReturn(location.getBlockZ());
            when(block.getWorld()).thenReturn(h.world);when(block.getLocation()).thenReturn(location.clone());when(block.getChunk()).thenReturn(chunk);when(block.getType()).thenReturn(material);
            when(block.getState()).thenReturn(mock(BlockState.class));when(block.getMetadata("Grave")).thenReturn(List.of());
            when(chunk.getPersistentDataContainer()).thenReturn(data);
            when(data.get(any(),eq(PersistentDataType.STRING))).thenAnswer(call->values.get(call.getArgument(0)));
            doAnswer(call->{values.put(call.getArgument(0),call.getArgument(2));return null;}).when(data).set(any(),eq(PersistentDataType.STRING),anyString());
            when(h.world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
        }
        void tag(ClaimHarness h,UUID token)throws Exception {
            GraveMarkerCleanup.tag(h.plugin,block,h.identity());values.put(PendingRecreationMarker.tokenKey(h.plugin,block),token.toString());
        }
    }
    @ParameterizedTest @EnumSource(value=Material.class,names={"CHEST","PLAYER_HEAD","BARRIER"})
    void pendingExactAttemptCancelsManualInteractionBreakAndEnvironmentalDamage(Material material)throws Exception {
        try(var h=new ClaimHarness()) {
            var backend=mock(GraveStorageBackend.class);var manager=SqlRecreationOwnerTest.manager(h,backend);SqlRecreationOwnerTest.install(h,manager);
            var marker=new Marker(h,material);var token=UUID.randomUUID();
            try(var journal=new SqlJournalHarness(h.io,backend,manager,directory);var admission=manager.reserveRecreation(h.grave.getGravesConfig(),true)) {
                assertNotNull(admission);assertTrue(admission.bindMarker(token,material));marker.tag(h,token);
                assertTrue(manager.hasPendingRecreationMarkers());assertTrue(PendingRecreationMarker.protects(h.plugin,marker.block));
                var claims=new GraveClaimListener(h.plugin);
                var breaking=mock(BlockBreakEvent.class);when(breaking.getBlock()).thenReturn(marker.block);claims.onBlockBreak(breaking);verify(breaking).setCancelled(true);
                var interact=mock(PlayerInteractEvent.class);when(interact.getClickedBlock()).thenReturn(marker.block);claims.onPlayerInteract(interact);verify(interact).setCancelled(true);
                var environment=new GraveBlockProtectionListener(h.plugin);var explosion=mock(BlockExplodeEvent.class);var blocks=new ArrayList<>(List.of(marker.block));when(explosion.blockList()).thenReturn(blocks);environment.onBlockExplode(explosion);assertTrue(blocks.isEmpty());
                var physics=mock(BlockPhysicsEvent.class);when(physics.getBlock()).thenReturn(marker.block);environment.onPhysics(physics);verify(physics).setCancelled(true);assertTrue(h.regionWork.isEmpty(),"identity-only repair must not be queued for provisional tile state");
                assertTrue(admission.cancelRetainingFence());assertTrue(PendingRecreationMarker.protects(h.plugin,marker.block),"cleanup still owns the physical fence");
                assertTrue(admission.finish());assertFalse(manager.hasPendingRecreationMarkers());assertFalse(PendingRecreationMarker.protects(h.plugin,marker.block));
                verify(backend,never()).saveRecreatedGrave(any());verify(marker.block,never()).setType(any(),anyBoolean());verify(h.owner,never()).giveExp(anyInt());
            }
        }
    }
    @ParameterizedTest @ValueSource(strings={"material","token","tag","world","position","successor"})
    void wrongPhysicalOwnershipNeverAcquiresProtection(String change)throws Exception {
        try(var h=new ClaimHarness()) {
            var backend=mock(GraveStorageBackend.class);var manager=SqlRecreationOwnerTest.manager(h,backend);SqlRecreationOwnerTest.install(h,manager);var marker=new Marker(h,Material.CHEST);var token=UUID.randomUUID();
            try(var journal=new SqlJournalHarness(h.io,backend,manager,directory);var admission=manager.reserveRecreation(h.grave.getGravesConfig(),true)) {
                assertNotNull(admission);assertTrue(admission.bindMarker(token,Material.CHEST));marker.tag(h,token);
                if(change.equals("material"))when(marker.block.getType()).thenReturn(Material.PLAYER_HEAD);
                if(change.equals("token"))marker.values.put(PendingRecreationMarker.tokenKey(h.plugin,marker.block),UUID.randomUUID().toString());
                if(change.equals("tag"))marker.values.remove(PendingRecreationMarker.tokenKey(h.plugin,marker.block));
                if(change.equals("world"))when(h.world.getUID()).thenReturn(UUID.randomUUID());
                if(change.equals("position")){when(marker.block.getY()).thenReturn(65);marker.tag(h,token);}
                if(change.equals("successor"))GraveMarkerCleanup.tag(h.plugin,marker.block,h.identity()+"-successor");
                assertFalse(PendingRecreationMarker.protects(h.plugin,marker.block));verify(marker.block,never()).setType(any(),anyBoolean());
            }
        }
    }
    @Test void queuedGenericPhysicsRepairCannotUseAProvisionalOrReplacementAttemptTag()throws Exception {
        try(var h=new ClaimHarness()) {
            var backend=mock(GraveStorageBackend.class);var manager=SqlRecreationOwnerTest.manager(h,backend);SqlRecreationOwnerTest.install(h,manager);var marker=new Marker(h,Material.CHEST);
            var restoration=mock(GraveRestoration.class);when(h.plugin.getGraveRestoration()).thenReturn(restoration);when(restoration.pendingMarkerIdentity(marker.block,Material.CHEST)).thenReturn(h.identity());
            var listener=new GraveBlockProtectionListener(h.plugin);
            var queue=GraveBlockProtectionListener.class.getDeclaredMethod("restoreIfRemovedNextTick",Block.class,Material.class);queue.setAccessible(true);queue.invoke(listener,marker.block,Material.CHEST);assertEquals(1,h.regionWork.size());
            try(var journal=new SqlJournalHarness(h.io,backend,manager,directory);var admission=manager.reserveRecreation(h.grave.getGravesConfig(),true)) {
                var token=UUID.randomUUID();assertNotNull(admission);assertTrue(admission.bindMarker(token,Material.CHEST));marker.tag(h,token);when(marker.block.getType()).thenReturn(Material.AIR);
                // A replaced token must not fall through to the old logical-identity repair either.
                marker.values.put(PendingRecreationMarker.tokenKey(h.plugin,marker.block),UUID.randomUUID().toString());
                h.regionWork.remove().run();verify(marker.block,never()).setType(any(),anyBoolean());
            }
        }
    }
    @Test void immutableBindingIsReleasedOnlyWithItsIdentityFence()throws Exception {
        try(var h=new ClaimHarness()) {
            var backend=mock(GraveStorageBackend.class);var manager=SqlRecreationOwnerTest.manager(h,backend);SqlRecreationOwnerTest.install(h,manager);var marker=new Marker(h,Material.CHEST);var token=UUID.randomUUID();
            try(var journal=new SqlJournalHarness(h.io,backend,manager,directory)) {
                var admission=manager.reserveRecreation(h.grave.getGravesConfig(),true);assertNotNull(admission);
                assertFalse(admission.bindMarker(null,Material.CHEST));assertFalse(admission.bindMarker(token,null));assertFalse(admission.bindMarker(token,Material.STONE));assertFalse(manager.hasPendingRecreationMarkers());
                assertTrue(admission.bindMarker(token,Material.CHEST));assertFalse(admission.bindMarker(token,Material.CHEST));assertFalse(admission.bindMarker(UUID.randomUUID(),Material.PLAYER_HEAD));marker.tag(h,token);
                var binding=manager.pendingRecreationMarker(h.identity());assertEquals(token,binding.token());assertEquals(Material.CHEST,binding.material());
                assertTrue(admission.cancelRetainingFence());admission.close();assertSame(binding,manager.pendingRecreationMarker(h.identity()));assertTrue(PendingRecreationMarker.protects(h.plugin,marker.block));
                assertTrue(admission.finish());assertNull(manager.pendingRecreationMarker(h.identity()));assertFalse(manager.hasPendingRecreationMarkers());
                try(var replacement=manager.reserveRecreation(h.grave.getGravesConfig(),true)) {
                    assertNotNull(replacement);assertTrue(replacement.bindMarker(UUID.randomUUID(),Material.CHEST));assertFalse(PendingRecreationMarker.protects(h.plugin,marker.block));
                }
                assertFalse(manager.hasPendingRecreationMarkers());verify(backend,never()).saveRecreatedGrave(any());
            }
        }
    }

}
