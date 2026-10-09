package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.gravestonesplus.storage.*;
import com.bencodez.gravestonesplus.listeners.GraveCleanupListener;
import com.bencodez.gravestonesplus.listeners.GraveClaimListener;
import com.bencodez.gravestonesplus.listeners.GraveBlockProtectionListener;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class MarkerRestorationRegressionTest {
    @TempDir Path directory;
    private GraveMarkerCleanup cleanup(GraveActionsTest.Harness h, DurableFeatureState state) throws Exception {
        var cleanup = new GraveMarkerCleanup(h.plugin, state); when(h.plugin.getMarkerCleanup()).thenReturn(cleanup); FeaturePersistenceTest.await(cleanup.loaded()); return cleanup;
    }
    private static final class Marker {
        final Block block=mock(Block.class); final Chunk chunk=mock(Chunk.class); final PersistentDataContainer chunkData=mock(PersistentDataContainer.class), tileData=mock(PersistentDataContainer.class);
        final Map<NamespacedKey,String> values=new HashMap<>(), tileValues=new HashMap<>();
        Marker(GraveActionsTest.Harness h) {
            when(h.world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(block); when(h.world.getBlockAt(any(Location.class))).thenReturn(block); when(h.world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
            when(block.getX()).thenReturn(1); when(block.getY()).thenReturn(64); when(block.getZ()).thenReturn(1); when(block.getWorld()).thenReturn(h.world); when(block.getChunk()).thenReturn(chunk);
            when(block.getType()).thenReturn(Material.PLAYER_HEAD); when(block.getMetadata("Grave")).thenReturn(List.of()); when(chunk.getPersistentDataContainer()).thenReturn(chunkData); when(chunk.getEntities()).thenReturn(new Entity[0]);
            TileState tile=mock(TileState.class); when(block.getState()).thenReturn(tile); when(tile.getPersistentDataContainer()).thenReturn(tileData);
            when(tileData.get(any(),eq(PersistentDataType.STRING))).thenAnswer(i->tileValues.get(i.getArgument(0)));
            doAnswer(i->{tileValues.put(i.getArgument(0),i.getArgument(2));return null;}).when(tileData).set(any(),eq(PersistentDataType.STRING),anyString());
            when(chunkData.get(any(),eq(PersistentDataType.STRING))).thenAnswer(i->values.get(i.getArgument(0)));
            doAnswer(i->{values.put(i.getArgument(0),i.getArgument(2));return null;}).when(chunkData).set(any(),eq(PersistentDataType.STRING),anyString());
            doAnswer(i->{values.remove(i.getArgument(0));return null;}).when(chunkData).remove(any());
        }
    }
    private GraveRestoration restoration(GraveActionsTest.Harness h, GravesConfig record,
            CompletableFuture<GraveClaimLedger.Restoration> initial, CompletableFuture<GraveClaimLedger.Restoration> chunk) {
        h.active.clear(); when(h.config.isDisableArmorStands()).thenReturn(true); when(h.config.getGraveTimeLimit()).thenReturn("0");
        var ledger = mock(GraveClaimLedger.class); when(ledger.restoration(any())).thenReturn(initial, chunk);
        when(h.plugin.getGraveClaimLedger()).thenReturn(ledger); when(h.plugin.getFeatureIO()).thenReturn(h.io);
        var backend = mock(GraveStorageManager.class); when(backend.loadGraves()).thenReturn(List.of(record)); when(backend.loadBrokenGraves()).thenReturn(List.of());
        doAnswer(i -> { h.active.add(i.getArgument(0)); return null; }).when(h.plugin).restoreActiveRecord(any());
        var restoration = new GraveRestoration(h.plugin, backend); when(h.plugin.getGraveRestoration()).thenReturn(restoration); return restoration;
    }
    private static Block neighbor(GraveActionsTest.Harness h, Material type, int x) {
        var block = mock(Block.class); when(block.getWorld()).thenReturn(h.world); when(block.getType()).thenReturn(type);
        when(block.getX()).thenReturn(x); when(block.getY()).thenReturn(64); when(block.getZ()).thenReturn(1); return block;
    }
    private void assertNativeAndEnvironmentalProtection(GraveActionsTest.Harness h, Block marker, Block neighbor) {
        var claims = new GraveClaimListener(h.plugin); var protection = new GraveBlockProtectionListener(h.plugin);
        var broken = new org.bukkit.event.block.BlockBreakEvent(marker, h.owner); claims.onBlockBreak(broken); assertTrue(broken.isCancelled());
        var adjacent = new org.bukkit.event.block.BlockBreakEvent(neighbor, h.owner); claims.onBlockBreak(adjacent); assertFalse(adjacent.isCancelled());
        var interact = new org.bukkit.event.player.PlayerInteractEvent(h.owner, org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                null, marker, org.bukkit.block.BlockFace.UP);
        claims.onPlayerInteract(interact); assertTrue(interact.isCancelled());
        when(h.owner.getEyeLocation()).thenReturn(new Location(h.world, 1, 64, 1));
        when(h.world.rayTraceBlocks(any(Location.class), any(org.bukkit.util.Vector.class), eq(6.0), eq(FluidCollisionMode.NEVER), eq(true)))
                .thenReturn(new org.bukkit.util.RayTraceResult(new org.bukkit.util.Vector(1, 64, 1), marker, org.bukkit.block.BlockFace.UP));
        claims.onPlayerAnimation(new org.bukkit.event.player.PlayerAnimationEvent(h.owner)); assertTrue(claims.getMining().isEmpty());
        var entityExplosion = mock(org.bukkit.event.entity.EntityExplodeEvent.class); var entityBlocks = new ArrayList<>(List.of(marker, neighbor));
        when(entityExplosion.blockList()).thenReturn(entityBlocks); protection.onEntityExplode(entityExplosion); assertEquals(List.of(neighbor), entityBlocks);
        var blockExplosion = mock(org.bukkit.event.block.BlockExplodeEvent.class); var blockBlocks = new ArrayList<>(List.of(marker, neighbor));
        when(blockExplosion.blockList()).thenReturn(blockBlocks); protection.onBlockExplode(blockExplosion); assertEquals(List.of(neighbor), blockBlocks);
        var fluid = mock(Block.class); when(fluid.getType()).thenReturn(Material.WATER);
        var flow = new org.bukkit.event.block.BlockFromToEvent(fluid, marker); protection.onBlockFromTo(flow); assertTrue(flow.isCancelled());
        var freeFlow = new org.bukkit.event.block.BlockFromToEvent(fluid, neighbor); protection.onBlockFromTo(freeFlow); assertFalse(freeFlow.isCancelled());
        var bucket = mock(org.bukkit.event.player.PlayerBucketEmptyEvent.class); when(bucket.getBlock()).thenReturn(marker);
        protection.onPlayerBucketEmpty(bucket); verify(bucket).setCancelled(true);
        var extend = mock(org.bukkit.event.block.BlockPistonExtendEvent.class); when(extend.getBlocks()).thenReturn(List.of(marker));
        protection.onPistonExtend(extend); verify(extend).setCancelled(true);
        var retract = mock(org.bukkit.event.block.BlockPistonRetractEvent.class); when(retract.getBlocks()).thenReturn(List.of(marker));
        protection.onPistonRetract(retract); verify(retract).setCancelled(true);
        var drops = mock(org.bukkit.event.block.BlockDropItemEvent.class); when(drops.getBlock()).thenReturn(marker);
        protection.onBlockDropItem(drops); verify(drops).setCancelled(true);
        var physics = mock(org.bukkit.event.block.BlockPhysicsEvent.class); when(physics.getBlock()).thenReturn(marker);
        protection.onPhysics(physics); verify(physics).setCancelled(true);
        verify(h.owner, never()).giveExp(anyInt()); verify(h.ownerInventory, never()).setItem(anyInt(), any());
        verify(h.events, never()).callEvent(any(com.bencodez.gravestonesplus.events.GraveClaimEvent.class));
    }
    @ParameterizedTest @EnumSource(value = Material.class, names = {"PLAYER_HEAD", "BARRIER", "CHEST"})
    void actualNativeAndEnvironmentalListenersProtectExactPendingMarkersAcrossArchiveAndChunkAdmission(Material type) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var marker = new Marker(h); when(marker.block.getType()).thenReturn(type); when(marker.block.getLocation()).thenReturn(h.grave.getGravesConfig().getLocation());
            h.grave.getGravesConfig().setGraveDisplayType(type == Material.BARRIER ? "DISPLAY_ENTITY" : type.name());
            when(marker.chunk.getWorld()).thenReturn(h.world); when(marker.chunk.getX()).thenReturn(0); when(marker.chunk.getZ()).thenReturn(0);
            // A barrier restored by this test needs no entity creation to exercise native protection.
            if (type == Material.BARRIER) when(h.plugin.getGraveDisplayEntityHandler()).thenReturn(mock(GraveDisplayEntityHandle.class));
            var initial = new CompletableFuture<GraveClaimLedger.Restoration>(); var chunk = new CompletableFuture<GraveClaimLedger.Restoration>();
            var restoration = restoration(h, h.grave.getGravesConfig(), initial, chunk);
            when(h.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
            restoration.retry(); FeaturePersistenceTest.await(h.io.barrier());
            when(h.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            var adjacent = neighbor(h, type, 2);
            assertNativeAndEnvironmentalProtection(h, marker.block, adjacent);
            assertTrue(h.active.isEmpty()); verify(marker.block, never()).setMetadata(anyString(), any());
            verify(h.plugin.getGraveClaimLedger(), times(1)).restoration(any());
            h.regionWork.remove().run(); // Deferred physics repair cannot create a second marker.
            when(h.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
            initial.complete(GraveClaimLedger.Restoration.AVAILABLE); h.regionWork.remove().run();
            assertEquals(1, h.active.size()); verify(marker.block, never()).setMetadata(anyString(), any());
            when(h.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            new GraveCleanupListener(h.plugin).onChunkLoad(new org.bukkit.event.world.ChunkLoadEvent(marker.chunk, false));
            assertNativeAndEnvironmentalProtection(h, marker.block, adjacent);
            assertTrue(restoration.hasPending(h.identity())); verify(marker.block, never()).setMetadata(anyString(), any());
            verify(h.plugin.getGraveClaimLedger(), times(2)).restoration(any());
            h.regionWork.remove().run();
            chunk.complete(GraveClaimLedger.Restoration.AVAILABLE); h.regionWork.remove().run();
            assertFalse(restoration.hasPending(h.identity())); assertFalse(restoration.protectsPendingMarker(marker.block));
            verify(marker.block, times(1)).setMetadata(eq("Grave"), any());
            verify(marker.block, never()).setType(any(Material.class), anyBoolean()); verify(h.world, never()).getChunkAt(anyInt(), anyInt());
            restoration.retire();
        }
    }
    @Test void exactPendingProtectionRejectsChangedRemovedAndSuccessorRecordsWithoutProtectingOtherWorlds() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var marker = new Marker(h); var admission = new CompletableFuture<GraveClaimLedger.Restoration>();
            var restoration = restoration(h, h.grave.getGravesConfig(), admission, admission);
            restoration.retry(); FeaturePersistenceTest.await(h.io.barrier()); assertTrue(restoration.protectsPendingMarker(marker.block));
            var otherWorld = mock(World.class); when(otherWorld.getUID()).thenReturn(UUID.randomUUID());
            var other = neighbor(h, Material.PLAYER_HEAD, 1); when(other.getWorld()).thenReturn(otherWorld);
            assertFalse(restoration.protectsPendingMarker(other)); assertFalse(restoration.protectsPendingMarker(neighbor(h, Material.PLAYER_HEAD, 2)));
            when(marker.block.getType()).thenReturn(Material.CHEST); assertFalse(restoration.protectsPendingMarker(marker.block));
            when(marker.block.getType()).thenReturn(Material.PLAYER_HEAD);
            h.grave.getGravesConfig().getLocation().setX(2); assertFalse(restoration.protectsPendingMarker(marker.block));
            h.grave.getGravesConfig().getLocation().setX(1);
            h.grave.getGravesConfig().setDestroyed(true); assertFalse(restoration.protectsPendingMarker(marker.block));
            var broken = new org.bukkit.event.block.BlockBreakEvent(marker.block, h.owner); new GraveClaimListener(h.plugin).onBlockBreak(broken); assertFalse(broken.isCancelled());
            h.grave.getGravesConfig().setDestroyed(false);
            GraveMarkerCleanup.tag(h.plugin, marker.block, h.identity() + "-successor"); assertFalse(restoration.protectsPendingMarker(marker.block));
            marker.values.clear(); marker.tileValues.clear(); assertTrue(restoration.protectsPendingMarker(marker.block));
            restoration.retire(); assertFalse(restoration.protectsPendingMarker(marker.block));
        }
    }
    @Test void pendingPhysicsRepairCannotResurrectARemovedRecordAfterAdmissionDelay() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var marker = new Marker(h); when(marker.block.getLocation()).thenReturn(h.grave.getGravesConfig().getLocation());
            var admission = new CompletableFuture<GraveClaimLedger.Restoration>();
            var restoration = restoration(h, h.grave.getGravesConfig(), admission, admission);
            restoration.retry(); FeaturePersistenceTest.await(h.io.barrier());
            var physics = mock(org.bukkit.event.block.BlockPhysicsEvent.class); when(physics.getBlock()).thenReturn(marker.block);
            new GraveBlockProtectionListener(h.plugin).onPhysics(physics); verify(physics).setCancelled(true);
            h.grave.getGravesConfig().setDestroyed(true); when(marker.block.getType()).thenReturn(Material.AIR);
            h.regionWork.remove().run(); verify(marker.block, never()).setType(any(Material.class), anyBoolean());
            assertFalse(restoration.protectsPendingMarker(marker.block)); restoration.retire();
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pendingPhysicsRepairRestoresOnlyItsStillOwnedMarker(boolean replaced) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var marker = new Marker(h); when(marker.block.getLocation()).thenReturn(h.grave.getGravesConfig().getLocation());
            var admission = new CompletableFuture<GraveClaimLedger.Restoration>();
            var restoration = restoration(h, h.grave.getGravesConfig(), admission, admission);
            restoration.retry(); FeaturePersistenceTest.await(h.io.barrier());
            var physics = mock(org.bukkit.event.block.BlockPhysicsEvent.class); when(physics.getBlock()).thenReturn(marker.block);
            new GraveBlockProtectionListener(h.plugin).onPhysics(physics); verify(physics).setCancelled(true);
            if (replaced) GraveMarkerCleanup.tag(h.plugin, marker.block, h.identity() + "-successor");
            when(marker.block.getType()).thenReturn(Material.AIR); h.regionWork.remove().run();
            if (replaced) verify(marker.block, never()).setType(any(Material.class), anyBoolean());
            else verify(marker.block).setType(Material.PLAYER_HEAD, false);
            verify(h.plugin.getGraveClaimLedger(), times(1)).restoration(any());
            verify(h.owner, never()).giveExp(anyInt()); restoration.retire();
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void actualRestorationPreservesAdoptedLegacyAndIdentityTaggedDisplaysAfterDelayedEntityCleanup(boolean alreadyTagged) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var marker = new Marker(h); when(marker.block.getType()).thenReturn(Material.BARRIER); when(marker.block.getLocation()).thenReturn(h.grave.getGravesConfig().getLocation());
            h.grave.getGravesConfig().setGraveDisplayType("DISPLAY_ENTITY"); UUID displayId = UUID.randomUUID(); h.grave.getGravesConfig().setDisplayUUID(displayId);
            var graveKey = new NamespacedKey(h.plugin, "grave");
            when(h.plugin.getKey()).thenReturn(graveKey);
            var display = mock(org.bukkit.entity.ItemDisplay.class); when(display.getUniqueId()).thenReturn(displayId); when(display.getType()).thenReturn(org.bukkit.entity.EntityType.ITEM_DISPLAY);
            var displayData = mock(PersistentDataContainer.class); when(display.getPersistentDataContainer()).thenReturn(displayData);
            Map<NamespacedKey, String> tags = new HashMap<>(); if (alreadyTagged) tags.put(GraveMarkerCleanup.identityKey(h.plugin), h.identity());
            when(displayData.get(any(), eq(PersistentDataType.STRING))).thenAnswer(i -> tags.get(i.getArgument(0)));
            when(displayData.get(h.plugin.getKey(), PersistentDataType.INTEGER)).thenReturn(1);
            doAnswer(i -> { tags.put(i.getArgument(0), i.getArgument(2)); return null; }).when(displayData).set(any(), eq(PersistentDataType.STRING), anyString());
            h.bukkit.when(() -> Bukkit.getEntity(displayId)).thenReturn(display);
            var stale = mock(org.bukkit.entity.ItemDisplay.class); var foreign = mock(org.bukkit.entity.ArmorStand.class);
            for (Entity entity : List.of(stale, foreign)) {
                when(entity.getType()).thenReturn(entity == stale ? org.bukkit.entity.EntityType.ITEM_DISPLAY : org.bukkit.entity.EntityType.ARMOR_STAND);
                var data = mock(PersistentDataContainer.class); when(entity.getPersistentDataContainer()).thenReturn(data);
                when(data.get(any(), eq(PersistentDataType.STRING))).thenReturn(entity == stale ? h.identity() : "successor-identity");
            }
            when(h.world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(display, stale, foreign));
            doAnswer(i -> { h.entityWork.add(i.getArgument(1)); return null; }).when(h.scheduler).runTask(eq(h.plugin), any(Runnable.class), any(Entity.class));
            // A predecessor's queued reconciliation must respect the new live instance's adoption too.
            h.grave.removeHologramsAround();
            var admission = new CompletableFuture<GraveClaimLedger.Restoration>(); var restoration = restoration(h, h.grave.getGravesConfig(), admission, admission);
            restoration.retry(); FeaturePersistenceTest.await(h.io.barrier()); admission.complete(GraveClaimLedger.Restoration.AVAILABLE); h.regionWork.remove().run();
            assertFalse(restoration.hasPending(h.identity())); assertEquals(1, h.active.size()); assertSame(display, h.active.getFirst().getItemDisplay());
            assertEquals(h.identity(), tags.get(GraveMarkerCleanup.identityKey(h.plugin))); verify(display, never()).remove();
            while (!h.entityWork.isEmpty()) h.entityWork.remove().run();
            assertSame(display, h.active.getFirst().getItemDisplay()); verify(display, never()).remove(); verify(stale, atLeastOnce()).remove(); verify(foreign, never()).remove();
            assertEquals(displayId, h.active.getFirst().getGravesConfig().getDisplayUUID()); verify(marker.block, never()).setType(any(Material.class), anyBoolean());
            restoration.retire();
        }
    }
    @Test void expiredUnloadedCleanupSurvivesHistoricalEvictionAndJournalRestartWithoutLoading() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            DurableFeatureState state=new DurableFeatureState(h.io,directory.resolve("cleanup.yml"),8192); var cleanup=cleanup(h,state); var marker=new Marker(h);
            GraveMarkerCleanup.tag(h.plugin,marker.block,h.identity()); when(h.world.isChunkLoaded(anyInt(),anyInt())).thenReturn(false);
            h.grave.removeGrave(com.bencodez.gravestonesplus.events.GraveRemoveReason.MANUAL_REMOVE); h.regionWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            h.regionWork.remove().run(); assertTrue(state.snapshot().containsKey(h.identity())); assertTrue(h.active.isEmpty()); verify(marker.block,never()).setType(Material.AIR); verify(h.world,never()).getChunkAt(anyInt(),anyInt());
            cleanup.retire(); var restarted=cleanup(h,new DurableFeatureState(h.io,directory.resolve("cleanup.yml"),8192)); when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of());
            when(h.world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true); when(marker.chunk.getX()).thenReturn(0); when(marker.chunk.getZ()).thenReturn(0); when(marker.chunk.getWorld()).thenReturn(h.world);
            new GraveCleanupListener(h.plugin).onChunkLoad(new org.bukkit.event.world.ChunkLoadEvent(marker.chunk,false)); h.regionWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier()); verify(marker.block).setType(Material.AIR); assertTrue(marker.values.isEmpty()); restarted.retire();
        }
    }
    @Test void oldCleanupNeverDeletesANewerTaggedMarkerAtSameCoordinates() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            var state=new DurableFeatureState(h.io,new FeaturePersistenceTest.Disk(),8192); var cleanup=cleanup(h,state); var marker=new Marker(h);
            h.active.clear(); h.grave.getGravesConfig().setDestroyed(true); GraveMarkerCleanup.tag(h.plugin,marker.block,h.identity()); assertTrue(FeaturePersistenceTest.await(cleanup.retain(h.grave.getGravesConfig())));
            GraveMarkerCleanup.tag(h.plugin,marker.block,h.identity()+"-newer"); h.regionWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            verify(marker.block,never()).setType(Material.AIR); assertTrue(marker.values.containsValue(h.identity()+"-newer")); assertTrue(state.snapshot().isEmpty()); cleanup.retire();
        }
    }
    @Test void normalizedMarkerTypesPublishCleanupWithoutLeakingShutdownAdmission() throws Exception {
        for (String raw : List.of("chest", " CHEST ", "display_entity", "player_head")) try (var h = new GraveActionsTest.Harness()) {
            var original = new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192);
            assertTrue(FeaturePersistenceTest.await(original.loaded()));
            var state = spy(original);
            var admission = new java.util.concurrent.atomic.AtomicReference<FeatureIO.Admission>();
            doAnswer(call -> {
                FeatureIO.Admission reserved = (FeatureIO.Admission) call.callRealMethod();
                admission.set(reserved); return reserved;
            }).when(state).admit();
            var cleanup = cleanup(h, state);
            try {
                h.grave.getGravesConfig().setGraveDisplayType(raw);
                var ticket = cleanup.prepare(h.grave.getGravesConfig()); assertNotNull(ticket);
                assertTrue(FeaturePersistenceTest.await(ticket.commit()));
                FeaturePersistenceTest.await(h.io.barrier());
                String expected = raw.trim().equalsIgnoreCase("display_entity") ? "BARRIER" : raw.trim().toUpperCase(Locale.ROOT);
                assertEquals(expected, state.snapshot().get(h.identity()));
                cleanup.retire(); h.io.close(); assertTrue(h.io.isTerminated(), "all cleanup admissions must finish before close returns");
            } finally {
                // Also release a reservation leaked by the pre-fix constructor path,
                // so a regression failure cannot wedge the entire test JVM shutdown.
                if (admission.get() != null) admission.get().close();
                cleanup.retire();
            }
        }
    }
    @Test void invalidMarkerConfigurationIsRejectedBeforeCapacityAdmission() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var original = new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192);
            assertTrue(FeaturePersistenceTest.await(original.loaded())); var state = spy(original);
            var admission = new java.util.concurrent.atomic.AtomicReference<FeatureIO.Admission>();
            doAnswer(call -> {
                FeatureIO.Admission reserved = (FeatureIO.Admission) call.callRealMethod(); admission.set(reserved); return reserved;
            }).when(state).admit();
            var cleanup = cleanup(h, state);
            try {
                h.grave.getGravesConfig().setGraveDisplayType("not-a-marker");
                assertNull(cleanup.prepare(h.grave.getGravesConfig())); verify(state, never()).admit();
                when(h.config.getGraveDisplayTypeEnum()).thenReturn(GraveDisplayType.CHEST);
                var ticket = cleanup.prepare(h.grave.getGravesConfig()); assertNotNull(ticket);
                ticket.close(); cleanup.retire(); h.io.close(); assertTrue(h.io.isTerminated());
            } finally { if (admission.get() != null) admission.get().close(); cleanup.retire(); }
        }
    }
    @Test void ticketRegistrationExceptionReleasesItsActualDiskAdmission() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var original = new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192);
            assertTrue(FeaturePersistenceTest.await(original.loaded())); var state = spy(original);
            var admission = new java.util.concurrent.atomic.AtomicReference<FeatureIO.Admission>();
            doAnswer(call -> {
                FeatureIO.Admission reserved = (FeatureIO.Admission) call.callRealMethod(); admission.set(reserved); return reserved;
            }).when(state).admit();
            var cleanup = cleanup(h, state);
            Set<GraveMarkerCleanup.Ticket> failingRegistry = new HashSet<>() {
                @Override public boolean add(GraveMarkerCleanup.Ticket ticket) { throw new IllegalStateException("injected ticket registration failure"); }
            };
            var registry = GraveMarkerCleanup.class.getDeclaredField("tickets"); registry.setAccessible(true); registry.set(cleanup, failingRegistry);
            var capacity = new ArrayList<FeatureIO.Admission>();
            try {
                assertThrows(IllegalStateException.class, () -> cleanup.prepare(h.grave.getGravesConfig()));
                FeatureIO.Admission reserved; while ((reserved = h.io.admit()) != null) capacity.add(reserved);
                assertEquals(129, capacity.size(), "a constructor/registration exception must release the admission it obtained");
            } finally {
                capacity.forEach(FeatureIO.Admission::close);
                if (admission.get() != null) admission.get().close();
                cleanup.retire();
            }
            h.io.close(); assertTrue(h.io.isTerminated());
        }
    }

    @Test void nativeChunkAdmissionBoundsThousandsOfUnavailableRecordsAndRetriesAfterCapacityReturns() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.active.clear(); when(h.plugin.getFeatureIO()).thenReturn(h.io);
            var records = new ArrayList<GravesConfig>();
            for (int i = 0; i < 5000; i++) records.add(new GravesConfig(h.owner.getUniqueId(), "Owner", new Location(h.world, 1, 64, 1),
                    new HashMap<>(), 0, "retained", 1000L + i, false, 0, null, null, "PLAYER_HEAD"));
            var backend = mock(GraveStorageManager.class); when(backend.loadGraves()).thenReturn(records); when(backend.loadBrokenGraves()).thenReturn(List.of());
            var initiallyUnavailable = mock(GraveClaimLedger.class);
            when(initiallyUnavailable.restoration(any())).thenReturn(CompletableFuture.completedFuture(GraveClaimLedger.Restoration.UNAVAILABLE));
            when(h.plugin.getGraveClaimLedger()).thenReturn(initiallyUnavailable);
            var restoration = new GraveRestoration(h.plugin, backend); when(h.plugin.getGraveRestoration()).thenReturn(restoration);
            restoration.retry(); FeaturePersistenceTest.await(h.io.barrier()); assertFalse(restoration.isLoading());
            var ledger = spy(h.ledger); var decisions = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> { decisions.incrementAndGet(); return call.callRealMethod(); }).when(ledger).restoration(any());
            when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            var chunk = mock(Chunk.class); when(chunk.getWorld()).thenReturn(h.world); when(chunk.getX()).thenReturn(0); when(chunk.getZ()).thenReturn(0);
            var event = new org.bukkit.event.world.ChunkLoadEvent(chunk, false); var listener = new GraveCleanupListener(h.plugin);
            var reservations = new ArrayList<FeatureIO.Admission>();
            try {
                FeatureIO.Admission admitted;
                while ((admitted = h.io.admit()) != null) reservations.add(admitted);
                assertEquals(129, reservations.size());
                listener.onChunkLoad(event); assertEquals(128, decisions.get());
                listener.onChunkLoad(event); assertEquals(256, decisions.get(), "UNAVAILABLE batches must release the running fence");
                assertTrue(h.active.isEmpty()); assertTrue(h.regionWork.isEmpty());
                assertTrue(restoration.hasPending(GraveIdentity.of(records.getLast())), "the remaining records stay indexed");
            } finally { reservations.forEach(FeatureIO.Admission::close); }
            try {
                listener.onChunkLoad(event); FeaturePersistenceTest.await(h.io.barrier());
                assertEquals(257, decisions.get()); assertEquals(1, h.regionWork.size(), "capacity recovery must admit the next native restoration callback");
                verify(backend, never()).restorationResolved(any());
            } finally {
                restoration.retire(); Runnable task; while ((task = h.regionWork.poll()) != null) task.run();
            }
        }
    }

    @Test void cleanupAdmissionFailurePreservesGraveAndContentsBeforeRemoval() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            DurableFeatureState.Disk disk=new DurableFeatureState.Disk() { public Map<String,String> load() throws Exception { throw new java.io.IOException("injected read failure"); } public void save(Map<String,String> values) {} };
            var cleanup=new GraveMarkerCleanup(h.plugin,new DurableFeatureState(h.io,disk,8192)); when(h.plugin.getMarkerCleanup()).thenReturn(cleanup); assertFalse(FeaturePersistenceTest.await(cleanup.loaded()));
            h.grave.removeGrave(com.bencodez.gravestonesplus.events.GraveRemoveReason.MANUAL_REMOVE); h.regionWork.remove().run(); assertFalse(h.grave.isRemove()); assertFalse(h.grave.getGravesConfig().isDestroyed()); assertEquals(17,h.grave.getGravesConfig().getExp()); verify(h.plugin,never()).removeGrave(any()); cleanup.retire();
        }
    }
    @Test void restoredStartupArchiveAdmissionPrecedesMetadataNavigationAndTimersForEveryDecision() throws Exception {
        for(var decision:List.of(GraveClaimLedger.Restoration.AVAILABLE,GraveClaimLedger.Restoration.PAID,GraveClaimLedger.Restoration.RESERVED,GraveClaimLedger.Restoration.UNAVAILABLE)) try(var h=new GraveActionsTest.Harness()) {
            h.active.clear(); when(h.config.isDisableArmorStands()).thenReturn(true); when(h.config.getGraveTimeLimit()).thenReturn("0"); when(h.config.getBrokenGraveTimeLimit()).thenReturn("7d"); var marker=new Marker(h);
            var cleanup=cleanup(h,new DurableFeatureState(h.io,new FeaturePersistenceTest.Disk(),8192)); var ledger=mock(GraveClaimLedger.class); CompletableFuture<GraveClaimLedger.Restoration> admission=new CompletableFuture<>(); when(ledger.restoration(any())).thenReturn(admission); when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            var backend=mock(GraveStorageManager.class); when(backend.loadGraves()).thenReturn(List.of(h.grave.getGravesConfig())); when(backend.loadBrokenGraves()).thenReturn(List.of()); when(h.plugin.getFeatureIO()).thenReturn(h.io);
            doAnswer(i->{h.active.add(i.getArgument(0));return null;}).when(h.plugin).restoreActiveRecord(any()); when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of());
            var restoration=new GraveRestoration(h.plugin,backend); restoration.retry(); FeaturePersistenceTest.await(h.io.barrier()); verify(marker.block,never()).getState(); assertTrue(h.active.isEmpty()); assertTrue(h.regionWork.isEmpty());
            admission.complete(decision); FeaturePersistenceTest.await(h.io.barrier()); Runnable task; while((task=h.regionWork.poll())!=null) task.run(); FeaturePersistenceTest.await(h.io.barrier());
            if(decision==GraveClaimLedger.Restoration.AVAILABLE) { assertEquals(1,h.active.size()); verify(marker.block,atLeastOnce()).getState(); verify(backend).restorationResolved(any()); }
            else { assertTrue(h.active.isEmpty()); verify(marker.block,never()).setMetadata(anyString(),any()); if(decision!=GraveClaimLedger.Restoration.PAID) verify(backend,never()).restorationResolved(any()); }
            restoration.retire(); cleanup.retire();
        }
    }
    @Test void partialRestorationShutdownRetainsUnknownStoredGraves() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            when(h.plugin.getFeatureIO()).thenReturn(h.io); when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of()); h.active.clear();
            var backend=mock(GraveStorageBackend.class); var manager=new GraveStorageManager(h.plugin); var field=GraveStorageManager.class.getDeclaredField("backend");field.setAccessible(true);field.set(manager,backend);
            manager.retainRestoration(List.of(h.grave.getGravesConfig()),List.of()); manager.saveGravesFromObjects(h.active); FeaturePersistenceTest.await(h.io.barrier());
            org.mockito.ArgumentCaptor<List<GravesConfig>> written=org.mockito.ArgumentCaptor.forClass(List.class); verify(backend).saveGraves(written.capture()); assertEquals(1,written.getValue().size()); assertEquals(17,written.getValue().getFirst().getExp());
        }
    }
    @Test void historicalOnlyStaffRecreationAndTeleportRecheckActualActorAuthority() throws Exception {
        for(String permission:List.of("GraveStonesPlus.AllBrokenGraves","GraveStonesPlus.AllBrokenGraves.Player")) try(var h=new GraveActionsTest.Harness()) {
            h.grave.getGravesConfig().setDestroyed(true); when(h.helper.hasPermission(permission)).thenReturn(true);
            var recreated=h.grave.recreateAsync(h.helper,false); h.drain(); h.regionWork.remove().run(); h.entityWork.remove().run(); assertTrue(FeaturePersistenceTest.await(recreated));
            h.grave.teleportHistorical(h.helper); h.entityWork.remove().run(); verify(h.platform).teleportAsync(eq(h.helper),any(Location.class));
            h.grave.getGravesConfig().setDestroyed(true); var revoked=h.grave.recreateAsync(h.helper,false); h.drain(); h.regionWork.remove().run(); when(h.helper.hasPermission(permission)).thenReturn(false); h.entityWork.remove().run(); assertFalse(FeaturePersistenceTest.await(revoked)); assertTrue(h.grave.getGravesConfig().isDestroyed());
            clearInvocations(h.platform); h.grave.teleportHistorical(h.helper); h.entityWork.remove().run(); verify(h.platform,never()).teleportAsync(any(),any());
        }
    }
    @Test void archiveAbsenceRejectionReservationPaidAndCorruptionAreDistinctOnActualLedger() throws Exception {
        try(FeatureIO io=new FeatureIO(1)) {
            Path journal=directory.resolve("claims.yml"), archive=directory.resolve("archive"); String id=FeaturePersistenceTest.GRAVE;
            var ledger=new GraveClaimLedger(new DurableFeatureState(io,journal,8192),archive); assertTrue(FeaturePersistenceTest.await(ledger.loaded()));
            assertEquals(GraveClaimLedger.Restoration.AVAILABLE,FeaturePersistenceTest.await(ledger.restoration(id)));
            CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1); var blocking=io.submit(()->{ entered.countDown(); try { assertTrue(release.await(3,TimeUnit.SECONDS)); } catch(InterruptedException error) {throw new RuntimeException(error);}return true;});
            assertTrue(entered.await(2,TimeUnit.SECONDS)); var queued=io.submit(()->true); assertEquals(GraveClaimLedger.Restoration.UNAVAILABLE,FeaturePersistenceTest.await(ledger.restoration(id))); release.countDown();FeaturePersistenceTest.await(blocking);FeaturePersistenceTest.await(queued);
            assertTrue(FeaturePersistenceTest.await(ledger.reserve(id,FeaturePersistenceTest.HELPER))); assertEquals(GraveClaimLedger.Restoration.RESERVED,FeaturePersistenceTest.await(ledger.restoration(id)));
            assertTrue(FeaturePersistenceTest.await(ledger.paid(id,FeaturePersistenceTest.HELPER))); var restarted=new GraveClaimLedger(new DurableFeatureState(io,journal,8192),archive);FeaturePersistenceTest.await(restarted.loaded()); assertEquals(GraveClaimLedger.Restoration.PAID,FeaturePersistenceTest.await(restarted.restoration(id)));
            String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))); Files.writeString(archive.resolve(hash+".yml"),"version: 1\nentries: {}\n");
            assertEquals(GraveClaimLedger.Restoration.UNAVAILABLE,FeaturePersistenceTest.await(restarted.restoration(id)));
        }
    }

    @Test void historicalRecreationWithSameIdentityRetiresOldIntentWithoutDeletingNewMarker() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            var state=new DurableFeatureState(h.io,new FeaturePersistenceTest.Disk(),8192); var cleanup=cleanup(h,state); var marker=new Marker(h);
            GraveMarkerCleanup.tag(h.plugin,marker.block,h.identity()); h.grave.getGravesConfig().setDestroyed(true); assertTrue(FeaturePersistenceTest.await(cleanup.retain(h.grave.getGravesConfig())));
            h.grave.markRecreated(); h.grave.getGravesConfig().setDestroyed(false); h.regionWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            verify(marker.block,never()).setType(Material.AIR); assertTrue(marker.values.containsValue(h.identity())); assertTrue(state.snapshot().isEmpty()); cleanup.retire();
        }
    }

    @Test void fullCleanupWorkerRejectsRemovalBeforeItemsOrBackendStateChange() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            var cleanup=cleanup(h,new DurableFeatureState(h.io,new FeaturePersistenceTest.Disk(),8192)); CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
            var blocking=h.io.submit(()->{entered.countDown();try {assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException error){throw new RuntimeException(error);}return true;}); assertTrue(entered.await(2,TimeUnit.SECONDS));
            List<CompletableFuture<?>> queued=new ArrayList<>(); for(int i=0;i<128;i++) queued.add(h.io.submit(()->true));
            h.grave.removeGrave(com.bencodez.gravestonesplus.events.GraveRemoveReason.MANUAL_REMOVE);h.regionWork.remove().run(); assertFalse(h.grave.isRemove()); assertFalse(h.grave.getGravesConfig().isDestroyed()); verify(h.plugin,never()).removeGrave(any());
            release.countDown(); FeaturePersistenceTest.await(blocking);FeaturePersistenceTest.await(CompletableFuture.allOf(queued.toArray(CompletableFuture[]::new))); cleanup.retire();
        }
    }
    @Test void markerTagsAndDisplayOwnershipCleanOnlyExactHeadChestOrBarrierIdentity() throws Exception {
        for(Material type:List.of(Material.PLAYER_HEAD,Material.CHEST,Material.BARRIER)) try(var h=new GraveActionsTest.Harness()) {
            var cleanup=cleanup(h,new DurableFeatureState(h.io,new FeaturePersistenceTest.Disk(),8192)); var marker=new Marker(h); h.active.clear(); h.grave.getGravesConfig().setDestroyed(true);
            h.grave.getGravesConfig().setGraveDisplayType(type==Material.BARRIER?"DISPLAY_ENTITY":type==Material.CHEST?"CHEST":"PLAYER_HEAD");when(marker.block.getType()).thenReturn(type); if(type==Material.BARRIER)when(marker.block.getState()).thenReturn(mock(BlockState.class));
            GraveMarkerCleanup.tag(h.plugin,marker.block,h.identity()); Entity owned=mock(Entity.class),other=mock(Entity.class); PersistentDataContainer ownedData=mock(PersistentDataContainer.class),otherData=mock(PersistentDataContainer.class); when(owned.getPersistentDataContainer()).thenReturn(ownedData);when(other.getPersistentDataContainer()).thenReturn(otherData);
            String identity=h.identity(); when(ownedData.get(any(),eq(PersistentDataType.STRING))).thenReturn(identity);when(otherData.get(any(),eq(PersistentDataType.STRING))).thenReturn("newer");when(marker.chunk.getEntities()).thenReturn(new Entity[]{owned,other});
            assertTrue(FeaturePersistenceTest.await(cleanup.retain(h.grave.getGravesConfig()))); h.regionWork.remove().run();FeaturePersistenceTest.await(h.io.barrier()); verify(marker.block).setType(Material.AIR);verify(owned).remove();verify(other,never()).remove();cleanup.retire();
        }
    }
    @Test void shutdownReleasesPreparedCleanupCapacityWithoutWaitingForQueuedPlayerPayout() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            var cleanup=cleanup(h,new DurableFeatureState(h.io,new FeaturePersistenceTest.Disk(),8192));h.grave.claim(h.owner);h.entityWork.remove().run();FeaturePersistenceTest.await(h.io.barrier()); assertFalse(h.entityWork.isEmpty());
            when(h.plugin.isEnabled()).thenReturn(false);cleanup.retire();assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),h.io::close);verify(h.owner,never()).giveExp(anyInt());assertTrue(h.ledger.isBlocked(h.identity()));
        }
    }

    @Test void unavailableStartupArchiveStillLoadsAndPreservesBackendRecordsBeforeShutdown() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            h.active.clear(); when(h.plugin.getFeatureIO()).thenReturn(h.io);when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of());
            var ledger=mock(GraveClaimLedger.class);when(ledger.loaded()).thenReturn(CompletableFuture.completedFuture(false));when(ledger.restoration(any())).thenReturn(CompletableFuture.completedFuture(GraveClaimLedger.Restoration.UNAVAILABLE));when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            var backend=mock(GraveStorageBackend.class);when(backend.loadGraves()).thenReturn(List.of(h.grave.getGravesConfig()));when(backend.loadBrokenGraves()).thenReturn(List.of());var manager=new GraveStorageManager(h.plugin);var field=GraveStorageManager.class.getDeclaredField("backend");field.setAccessible(true);field.set(manager,backend);
            var restoration=new GraveRestoration(h.plugin,manager);restoration.start();FeaturePersistenceTest.await(h.io.barrier());manager.saveGravesFromObjects(h.active);FeaturePersistenceTest.await(h.io.barrier());
            org.mockito.ArgumentCaptor<List<GravesConfig>> written=org.mockito.ArgumentCaptor.forClass(List.class);verify(backend).saveGraves(written.capture());assertEquals(17,written.getValue().getFirst().getExp());assertTrue(h.regionWork.isEmpty());assertTrue(h.active.isEmpty());restoration.retire();
        }
    }
    @Test void shutdownSnapshotQueuedBeforeBackendReadCompletesMergesRecordsAtPublicationBoundary() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            h.active.clear();when(h.plugin.getFeatureIO()).thenReturn(h.io);when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of());var backend=mock(GraveStorageBackend.class);var manager=new GraveStorageManager(h.plugin);var field=GraveStorageManager.class.getDeclaredField("backend");field.setAccessible(true);field.set(manager,backend);manager.beginRestoration();
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);var read=h.io.submit(()->{entered.countDown();try {assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException error){throw new RuntimeException(error);}manager.retainRestoration(List.of(h.grave.getGravesConfig()),List.of());return true;});assertTrue(entered.await(2,TimeUnit.SECONDS));
            manager.saveGravesFromObjects(h.active); manager.saveGravesFromObjects(h.active); release.countDown();FeaturePersistenceTest.await(read);FeaturePersistenceTest.await(h.io.barrier());org.mockito.ArgumentCaptor<List<GravesConfig>> written=org.mockito.ArgumentCaptor.forClass(List.class);verify(backend).saveGraves(written.capture());for (var snapshot : written.getAllValues()) { assertEquals(1,snapshot.size()); assertEquals(17,snapshot.getFirst().getExp()); }
        }
    }

    @Test void queuedPreAdmissionSnapshotsPreserveRecordsButFollowingRemovalDoesNotResurrectThem() throws Exception {
        for (boolean broken : List.of(false, true)) try (var h = new GraveActionsTest.Harness()) {
            when(h.plugin.getFeatureIO()).thenReturn(h.io);
            var backend = mock(GraveStorageBackend.class); var manager = new GraveStorageManager(h.plugin);
            var field = GraveStorageManager.class.getDeclaredField("backend"); field.setAccessible(true); field.set(manager, backend);
            manager.retainRestoration(broken ? List.of() : List.of(h.grave.getGravesConfig()), broken ? List.of(h.grave.getGravesConfig()) : List.of());
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var blocked = h.io.submit(() -> { entered.countDown(); try { assertTrue(release.await(3, TimeUnit.SECONDS)); } catch (InterruptedException failure) { throw new RuntimeException(failure); } return true; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try {
                for (int i = 0; i < 2; i++) {
                    if (broken) manager.saveBrokenGravesFromObjects(List.of()); else manager.saveGravesFromObjects(List.of());
                }
                manager.restorationResolved(h.grave.getGravesConfig());
                // A later actual removal publishes an empty list after its predecessors.
                if (broken) manager.saveBrokenGravesFromObjects(List.of()); else manager.saveGravesFromObjects(List.of());
            } finally { release.countDown(); }
            FeaturePersistenceTest.await(blocked); FeaturePersistenceTest.await(h.io.barrier());
            org.mockito.ArgumentCaptor<List<GravesConfig>> written = org.mockito.ArgumentCaptor.forClass(List.class);
            if (broken) verify(backend, times(3)).saveBrokenGraves(written.capture()); else verify(backend, times(3)).saveGraves(written.capture());
            assertEquals(17, written.getAllValues().get(0).getFirst().getExp()); assertEquals(17, written.getAllValues().get(1).getFirst().getExp()); assertTrue(written.getAllValues().get(2).isEmpty());
        }
    }

    @Test void backendPublicationNeverHoldsTheGameplayAdmissionLockDuringDiskWork() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            when(h.plugin.getFeatureIO()).thenReturn(h.io);when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of());var backend=mock(GraveStorageBackend.class);var manager=new GraveStorageManager(h.plugin);var field=GraveStorageManager.class.getDeclaredField("backend");field.setAccessible(true);field.set(manager,backend);
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);doAnswer(i->{entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return null;}).when(backend).saveGraves(anyList());manager.saveGravesFromObjects(h.active);assertTrue(entered.await(2,TimeUnit.SECONDS));
            try { assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->manager.saveGravesFromObjects(h.active)); } finally {release.countDown();}FeaturePersistenceTest.await(h.io.barrier());
        }
    }

    @Test void hologramRegistrationAndRetirementUseTheSameSharedRegistryMonitor() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var registry = spy(h.plugin.getHologramHandler()); when(h.plugin.getHologramHandler()).thenReturn(registry);
            var create = Grave.class.getDeclaredMethod("newManagedHologram", Location.class, String.class, boolean.class, boolean.class);
            create.setAccessible(true);
            try (var constructors = mockConstruction(com.bencodez.advancedcore.api.hologram.Hologram.class,
                    (hologram, context) -> assertTrue(Thread.holdsLock(registry)))) {
                var hologram = (com.bencodez.advancedcore.api.hologram.Hologram) create.invoke(h.grave,h.grave.getGravesConfig().getLocation(),"test",true,false);
                doAnswer(i -> { assertTrue(Thread.holdsLock(registry)); return null; }).when(registry).remove(hologram,false);
                var release = Grave.class.getDeclaredMethod("releaseHologram",com.bencodez.advancedcore.api.hologram.Hologram.class);
                release.setAccessible(true); release.invoke(h.grave,hologram);
                verify(registry).remove(hologram,false); assertEquals(1,constructors.constructed().size());
            }
        }
    }
    @Test void hologramIdentityTagWaitsForDeferredEntityAndRemovesRetiredCreation() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            var hologram=mock(com.bencodez.advancedcore.api.hologram.Hologram.class);var stand=mock(org.bukkit.entity.ArmorStand.class);var data=mock(PersistentDataContainer.class);when(stand.getPersistentDataContainer()).thenReturn(data);
            var top=Grave.class.getDeclaredField("topHologram");top.setAccessible(true);top.set(h.grave,hologram);
            doAnswer(i->{h.regionWork.add(i.getArgument(1));return null;}).when(h.scheduler).runTaskLater(eq(h.plugin),any(Runnable.class),eq(1L),any(Location.class));h.grave.tagHologram(hologram,0);assertEquals(1,h.regionWork.size());
            when(hologram.getArmorStand()).thenReturn(stand);h.regionWork.remove().run();NamespacedKey key=GraveMarkerCleanup.identityKey(h.plugin);String id=h.identity();verify(data).set(eq(key),eq(PersistentDataType.STRING),eq(id));
            when(hologram.getArmorStand()).thenReturn(null);h.grave.tagHologram(hologram,0);h.grave.getGravesConfig().setDestroyed(true);when(hologram.getArmorStand()).thenReturn(stand);h.regionWork.remove().run();verify(stand,never()).remove();h.entityWork.remove().run();verify(stand).remove();
        }
    }
    @Test void exactOwnedSurvivingHologramIsRemovedButUnownedAndSuccessorEntitiesRemain() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var marker = new Marker(h); when(marker.block.getLocation()).thenReturn(h.grave.getGravesConfig().getLocation());
            var owned = mock(org.bukkit.entity.ArmorStand.class); var legacy = mock(org.bukkit.entity.ArmorStand.class); var successor = mock(org.bukkit.entity.ArmorStand.class);
            String identity = h.identity();
            for (var entity : List.of(owned, legacy, successor)) {
                when(entity.getType()).thenReturn(org.bukkit.entity.EntityType.ARMOR_STAND); var data = mock(PersistentDataContainer.class); when(entity.getPersistentDataContainer()).thenReturn(data);
                when(data.get(any(), eq(PersistentDataType.STRING))).thenReturn(entity == owned ? identity : entity == successor ? "different-grave" : null);
            }
            when(h.world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(owned, legacy, successor));
            h.grave.removeHologramsAround(); verify(owned, never()).remove();
            while (!h.entityWork.isEmpty()) h.entityWork.remove().run();
            verify(owned).remove(); verify(legacy, never()).remove(); verify(successor, never()).remove();
        }
    }
    @Test void legacyPluginTaggedHologramsMigrateAtExactOwnedPositionsWithoutTouchingNeighbors() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var marker = new Marker(h); when(marker.block.getLocation()).thenReturn(h.grave.getGravesConfig().getLocation());
            var ownership = mock(org.bukkit.metadata.MetadataValue.class); when(ownership.getOwningPlugin()).thenReturn(h.plugin); when(ownership.value()).thenReturn(h.grave);
            when(marker.block.getMetadata("Grave")).thenReturn(List.of(ownership));
            var lines = new ArrayList<org.bukkit.entity.ArmorStand>();
            for (double height : new double[]{1.5, 1.25, 1, -2, 2, 1.5}) {
                var stand = mock(org.bukkit.entity.ArmorStand.class); lines.add(stand);
                when(stand.getType()).thenReturn(org.bukkit.entity.EntityType.ARMOR_STAND);
                var data = mock(PersistentDataContainer.class); when(stand.getPersistentDataContainer()).thenReturn(data);
                when(data.get(h.plugin.getKey(), PersistentDataType.INTEGER)).thenReturn(1);
                Location position = marker.block.getLocation().clone().add(.5, height, .5);
                if (lines.size() == 6) position.add(1,0,0);
                when(stand.getLocation()).thenReturn(position);
            }
            when(h.world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble())).thenReturn(new ArrayList<Entity>(lines));
            h.grave.removeHologramsAround();
            for (var line : lines) verify(line, never()).getPersistentDataContainer();
            while (!h.entityWork.isEmpty()) h.entityWork.remove().run();
            for (int i=0; i<lines.size(); i++) {
                if (i<4) {
                    verify(lines.get(i).getPersistentDataContainer()).set(GraveMarkerCleanup.identityKey(h.plugin), PersistentDataType.STRING, h.identity());
                    verify(lines.get(i)).remove();
                } else verify(lines.get(i), never()).remove();
            }
        }
    }
    @Test void queuedLegacyMigrationDoesNotAdoptAnotherGravesReplacementMarker() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var marker = new Marker(h); when(marker.block.getLocation()).thenReturn(h.grave.getGravesConfig().getLocation());
            var stand = mock(org.bukkit.entity.ArmorStand.class); when(stand.getType()).thenReturn(org.bukkit.entity.EntityType.ARMOR_STAND);
            var data = mock(PersistentDataContainer.class); when(stand.getPersistentDataContainer()).thenReturn(data);
            when(data.get(h.plugin.getKey(), PersistentDataType.INTEGER)).thenReturn(1);
            Location legacyLocation = marker.block.getLocation().clone().add(.5,1.5,.5);
            when(stand.getLocation()).thenReturn(legacyLocation);
            when(h.world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(stand));
            h.grave.removeHologramsAround();
            when(marker.block.getMetadata("Grave")).thenReturn(List.of());
            h.entityWork.remove().run(); verify(stand, never()).remove(); verify(data, never()).set(any(), eq(PersistentDataType.STRING), anyString());
        }
    }
    @Test void navigationMenuHonorsExistingCompassAndClaimPermissionsAtClickTime() throws Exception {
        try (var h = new GraveActionsTest.Harness(); var builders = mockConstruction(com.bencodez.advancedcore.api.item.ItemBuilder.class, withSettings().defaultAnswer(RETURNS_SELF))) {
            when(h.config.getGraveClaimDistance()).thenReturn(10);
            var button = h.grave.getNavigationGUIItem(h.owner);
            var click = mock(com.bencodez.advancedcore.api.inventory.BInventory.ClickEvent.class); when(click.getPlayer()).thenReturn(h.owner);
            when(click.getClick()).thenReturn(org.bukkit.event.inventory.ClickType.RIGHT); button.onClick(click);
            when(click.getClick()).thenReturn(org.bukkit.event.inventory.ClickType.LEFT); button.onClick(click);
            assertEquals(2, h.entityWork.size(), "denied clicks defer all player checks to the entity owner");
            verify(h.owner, never()).hasPermission("GraveStonesPlus.ClaimGrave");
            verify(h.owner, never()).hasPermission("GraveStonesPlus.Compass");
            h.drain();
            assertTrue(h.entityWork.isEmpty()); assertTrue(h.regionWork.isEmpty()); assertEquals(17, h.grave.getGravesConfig().getExp());
            verify(h.owner).hasPermission("GraveStonesPlus.ClaimGrave"); verify(h.owner).hasPermission("GraveStonesPlus.Compass");
            verify(h.owner, never()).setCompassTarget(any()); verify(h.ownerInventory, never()).addItem(any(org.bukkit.inventory.ItemStack[].class));
        }
    }

    @Test void unloadedAvailableStartupRetainsOwnerHelperNavigationAndCompassThenRestoresMarkerOnce() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.grant(TrustedGraveAccess.Kind.TRACK); h.active.clear();
            when(h.config.isDisableArmorStands()).thenReturn(true); when(h.config.getGraveTimeLimit()).thenReturn("1m");
            when(h.plugin.getTimer()).thenReturn(mock(ScheduledExecutorService.class));
            var marker = new Marker(h); when(h.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
            var ledger = mock(GraveClaimLedger.class);
            when(ledger.restoration(any())).thenReturn(CompletableFuture.completedFuture(GraveClaimLedger.Restoration.AVAILABLE));
            when(ledger.loaded()).thenReturn(CompletableFuture.completedFuture(true)); when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            var backend = mock(GraveStorageManager.class); when(backend.loadGraves()).thenReturn(List.of(h.grave.getGravesConfig())); when(backend.loadBrokenGraves()).thenReturn(List.of());
            when(h.plugin.getFeatureIO()).thenReturn(h.io); doAnswer(i -> { h.active.add(i.getArgument(0)); return null; }).when(h.plugin).restoreActiveRecord(any());
            var restoration = new GraveRestoration(h.plugin, backend); when(h.plugin.getGraveRestoration()).thenReturn(restoration);
            var compass = mock(org.bukkit.inventory.ItemStack.class); var meta = mock(org.bukkit.inventory.meta.CompassMeta.class); var data = mock(PersistentDataContainer.class);
            when(compass.getType()).thenReturn(Material.COMPASS); when(compass.getItemMeta()).thenReturn(meta); when(meta.getPersistentDataContainer()).thenReturn(data);
            String id = h.identity(); when(data.get(any(), eq(PersistentDataType.STRING))).thenReturn(id); when(h.ownerInventory.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[] {compass});
            h.navigation.refresh(h.owner); verify(data, never()).remove(any()); // Loading must not erase a persisted target.
            restoration.retry(); FeaturePersistenceTest.await(h.io.barrier()); h.regionWork.remove().run();
            assertEquals(1, h.active.size()); Grave admitted = h.active.getFirst();
            assertEquals(h.grave.getGravesConfig().getTime() + 60_000L, admitted.getExpiryDeadline());
            assertTrue(h.navigation.select(h.owner, admitted)); assertTrue(h.navigation.select(h.helper, admitted));
            assertSame(admitted, h.navigation.target(h.owner)); assertSame(admitted, h.navigation.target(h.helper));
            assertEquals(List.of(admitted), h.plugin.getAccessibleGraves(h.owner));
            h.navigation.refresh(h.owner); verify(data, never()).remove(any()); verify(marker.block, never()).getState(); verify(h.world, never()).getChunkAt(anyInt(), anyInt());
            when(h.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            restoration.onChunk(h.world.getUID(), 0, 0); FeaturePersistenceTest.await(h.io.barrier()); h.regionWork.remove().run();
            assertEquals(1, h.active.size()); assertSame(admitted, h.active.getFirst()); verify(backend, times(1)).restorationResolved(any());
            verify(marker.block, times(1)).setMetadata(eq("Grave"), any());
            restoration.onChunk(h.world.getUID(), 0, 0); FeaturePersistenceTest.await(h.io.barrier()); assertTrue(h.regionWork.isEmpty()); restoration.retire();
        }
    }
    @Test void initiallyLoadedInvalidMarkerIsNeverPublishedAsAnActiveRecord() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.active.clear(); var marker = new Marker(h); when(marker.block.getType()).thenReturn(Material.STONE);
            var cleanup = cleanup(h, new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192));
            var ledger = mock(GraveClaimLedger.class); when(ledger.restoration(any())).thenReturn(CompletableFuture.completedFuture(GraveClaimLedger.Restoration.AVAILABLE));
            when(h.plugin.getGraveClaimLedger()).thenReturn(ledger); when(h.config.getBrokenGraveTimeLimit()).thenReturn("7d");
            var backend = mock(GraveStorageManager.class); when(backend.loadGraves()).thenReturn(List.of(h.grave.getGravesConfig())); when(backend.loadBrokenGraves()).thenReturn(List.of());
            when(h.plugin.getFeatureIO()).thenReturn(h.io); when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of());
            var restoration = new GraveRestoration(h.plugin, backend); restoration.retry(); FeaturePersistenceTest.await(h.io.barrier()); h.regionWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            GraveActionsTest.drainGround(h);
            verify(h.plugin, never()).restoreActiveRecord(any()); verify(h.plugin).restoreBrokenRecord(any()); assertTrue(h.active.isEmpty()); verify(marker.block, never()).setMetadata(eq("Grave"), any());
            restoration.retire(); cleanup.retire();
        }
    }
    @Test void committedFinalCleanupSlotCannotBeReservedByAnotherGraveBeforeAcknowledgment() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var disk = new FeaturePersistenceTest.Disk(); var values = new HashMap<String, String>();
            for (int i = 0; i < 8191; i++) values.put(h.owner.getUniqueId() + "/" + i + "/" + h.world.getUID() + "/2/64/2", "PLAYER_HEAD");
            disk.values = values; var state = new DurableFeatureState(h.io, disk, 8192); var cleanup = cleanup(h, state);
            var first = new GravesConfig(h.owner.getUniqueId(), "Owner", new Location(h.world, 3, 64, 3), new HashMap<>(), 0,
                    "test", System.currentTimeMillis(), false, 0, null, null, "PLAYER_HEAD");
            var ticket = cleanup.prepare(first); assertNotNull(ticket);
            disk.entered = new CountDownLatch(1); disk.release = new CountDownLatch(1); var write = ticket.commit();
            assertTrue(disk.entered.await(2, TimeUnit.SECONDS));
            try {
                h.grave.removeGrave(com.bencodez.gravestonesplus.events.GraveRemoveReason.MANUAL_REMOVE); h.regionWork.remove().run();
                assertFalse(h.grave.isRemove()); assertEquals(17, h.grave.getGravesConfig().getExp()); verify(h.plugin, never()).removeGrave(any());
                assertNull(cleanup.prepare(h.grave.getGravesConfig()));
            } finally { disk.release.countDown(); }
            assertTrue(FeaturePersistenceTest.await(write)); assertEquals(8192, state.snapshot().size()); assertNull(cleanup.prepare(h.grave.getGravesConfig())); cleanup.retire();
        }
    }
    @Test void expirationRetriesWorkerAdmissionAndRemovesOnceWhenCapacityReturns() throws Exception {
        expirationAdmissionRetry(false);
    }
    @Test void disabledDeadlineAndPluginShutdownInvalidateAnAdmittedExpirationRetry() throws Exception {
        expirationAdmissionRetry(true);
    }
    private void expirationAdmissionRetry(boolean invalidate) throws Exception {
        for (boolean shutdown : invalidate ? List.of(false, true) : List.of(false)) try (var h = new GraveActionsTest.Harness()) {
            var cleanup = cleanup(h, new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192));
            var timer = mock(ScheduledExecutorService.class); var future = mock(ScheduledFuture.class);
            when(h.plugin.getTimer()).thenReturn(timer); List<Runnable> retries = new ArrayList<>();
            doAnswer(i -> { retries.add(i.getArgument(0)); return future; }).when(timer).schedule(any(Runnable.class), eq(5L), eq(TimeUnit.SECONDS));
            var deadline = Grave.class.getDeclaredField("expiryDeadline"); deadline.setAccessible(true); deadline.setLong(h.grave, System.currentTimeMillis() - 1);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            var blocking = h.io.submit(() -> { entered.countDown(); try { assertTrue(release.await(3, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); } return true; });
            assertTrue(entered.await(2, TimeUnit.SECONDS)); List<CompletableFuture<?>> queued = new ArrayList<>();
            for (int i = 0; i < 128; i++) queued.add(h.io.submit(() -> true));
            try {
                h.grave.removeGrave(com.bencodez.gravestonesplus.events.GraveRemoveReason.EXPIRED); h.regionWork.remove().run();
                assertFalse(h.grave.isRemove()); assertEquals(1, retries.size());
            } finally { release.countDown(); }
            FeaturePersistenceTest.await(blocking); FeaturePersistenceTest.await(CompletableFuture.allOf(queued.toArray(CompletableFuture[]::new)));
            if (invalidate) { if (shutdown) when(h.plugin.isEnabled()).thenReturn(false); else h.grave.checkTimeLimit("0"); }
            retries.getFirst().run(); h.regionWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            if (invalidate) { assertFalse(h.grave.isRemove()); verify(h.plugin, never()).removeGrave(any()); }
            else { assertTrue(h.grave.isRemove()); verify(h.plugin, times(1)).removeGrave(h.grave); Runnable task; while ((task = h.regionWork.poll()) != null) task.run(); retries.getFirst().run(); h.regionWork.remove().run(); verify(h.plugin, times(1)).removeGrave(h.grave); }
            cleanup.retire();
        }
    }
    @Test void glowRefreshUsesRegionOwnershipAndNeverProbesUnloadedMarkers() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var marker = new Marker(h); when(h.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false); when(h.config.isGlowingEffectNearGrave()).thenReturn(true);
            doCallRealMethod().when(h.plugin).refreshGraveGlow(any()); h.plugin.refreshGraveGlow(h.grave);
            verify(h.world, never()).isChunkLoaded(anyInt(), anyInt()); h.regionWork.remove().run(); verify(marker.block, never()).getType(); verify(h.world, never()).getChunkAt(anyInt(), anyInt());
        }
    }

    @Test void missingWorldYamlRecordRemainsSerializableThroughUnionSaveAndLaterRestores() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            when(h.plugin.getFeatureIO()).thenReturn(h.io); when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of());
            UUID missing = UUID.randomUUID();
            var legacy = new HashMap<String,Object>(); legacy.put("UUID",h.owner.getUniqueId().toString()); legacy.put("PlayerName","Owner");
            legacy.put("World",missing.toString()); legacy.put("X",1);legacy.put("Y",64);legacy.put("Z",1);legacy.put("EXP",17);legacy.put("Time",12345L);legacy.put("Items", new HashMap<Integer,org.bukkit.inventory.ItemStack>());
            var unresolved=GravesConfig.deserialize(legacy); assertNull(unresolved.getLocation().getWorld()); assertEquals(missing,unresolved.getWorldId());
            assertEquals(missing.toString(),unresolved.serialize().get("World")); assertNotNull(GraveIdentity.of(unresolved));
            var backend=mock(GraveStorageBackend.class); var manager=new GraveStorageManager(h.plugin);
            var field=GraveStorageManager.class.getDeclaredField("backend");field.setAccessible(true);field.set(manager,backend);
            manager.retainRestoration(List.of(unresolved),List.of());
            doAnswer(i -> { List<GravesConfig> records = i.getArgument(0); for (GravesConfig grave : records) grave.serialize(); return null; }).when(backend).saveGraves(any());
            manager.saveGravesFromObjects(h.active); FeaturePersistenceTest.await(h.io.barrier());
            org.mockito.ArgumentCaptor<List<GravesConfig>> written=org.mockito.ArgumentCaptor.forClass(List.class);verify(backend).saveGraves(written.capture());
            assertEquals(2,written.getValue().size()); assertEquals(missing,written.getValue().getFirst().getWorldId()); assertEquals(17,written.getValue().getFirst().getExp());
            // The same pending identity can be admitted when the world becomes available later.
            h.active.clear(); when(backend.loadGraves()).thenReturn(List.of(unresolved));when(backend.loadBrokenGraves()).thenReturn(List.of());
            when(h.config.isDisableArmorStands()).thenReturn(true);when(h.config.getGraveTimeLimit()).thenReturn("0"); var marker=new Marker(h);
            doAnswer(i -> { h.active.add(i.getArgument(0));return null; }).when(h.plugin).restoreActiveRecord(any());
            var decisions = new LinkedBlockingQueue<CompletableFuture<GraveClaimLedger.Restoration>>();
            var ledger = mock(GraveClaimLedger.class); when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            when(ledger.restoration(any())).thenAnswer(i -> new CompletableFuture<GraveClaimLedger.Restoration>() {
                @Override public CompletableFuture<GraveClaimLedger.Restoration> whenComplete(java.util.function.BiConsumer<? super GraveClaimLedger.Restoration, ? super Throwable> action) {
                    var registered = super.whenComplete(action);
                    decisions.add(this); // Publish only after the production continuation is installed.
                    return registered;
                }
            });
            var restoration=new GraveRestoration(h.plugin,manager);restoration.retry();
            Objects.requireNonNull(decisions.poll(5,TimeUnit.SECONDS)).complete(GraveClaimLedger.Restoration.AVAILABLE);
            FeaturePersistenceTest.await(h.io.barrier()); // Wait until the archive callback has released this restoration cycle.
            assertTrue(h.regionWork.isEmpty());
            h.bukkit.when(() -> Bukkit.getWorld(missing)).thenReturn(h.world);restoration.retry();
            Objects.requireNonNull(decisions.poll(5,TimeUnit.SECONDS)).complete(GraveClaimLedger.Restoration.AVAILABLE);h.regionWork.remove().run();
            assertEquals(1,h.active.size());assertEquals(missing,h.active.getFirst().getGravesConfig().getWorldId()); verify(h.world,never()).getChunkAt(anyInt(),anyInt());restoration.retire();
        }
    }
    @Test void staleChunkTagCannotDeleteAnUntaggedReplacementChest() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            h.active.clear();h.grave.getGravesConfig().setDestroyed(true);h.grave.getGravesConfig().setGraveDisplayType("CHEST");
            var state=new DurableFeatureState(h.io,new FeaturePersistenceTest.Disk(),8192);var cleanup=cleanup(h,state);var marker=new Marker(h);when(marker.block.getType()).thenReturn(Material.CHEST);
            GraveMarkerCleanup.tag(h.plugin,marker.block,h.identity());marker.tileValues.clear();
            assertTrue(FeaturePersistenceTest.await(cleanup.retain(h.grave.getGravesConfig())));h.regionWork.remove().run();FeaturePersistenceTest.await(h.io.barrier());
            verify(marker.block,never()).setType(Material.AIR);assertTrue(state.snapshot().containsKey(h.identity()));assertTrue(marker.values.containsValue(h.identity()));cleanup.retire();
        }
    }
    @Test void startupCleanupWaitsUntilPendingRecreatedIdentityIsAdmitted() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            var state=new DurableFeatureState(h.io,new FeaturePersistenceTest.Disk(),8192);var cleanup=cleanup(h,state);var marker=new Marker(h);
            GraveMarkerCleanup.tag(h.plugin,marker.block,h.identity());h.active.clear();h.grave.getGravesConfig().setDestroyed(true);
            assertTrue(FeaturePersistenceTest.await(cleanup.retain(h.grave.getGravesConfig())));
            var backend=mock(GraveStorageManager.class);h.grave.getGravesConfig().setDestroyed(false);when(backend.loadGraves()).thenReturn(List.of(h.grave.getGravesConfig()));when(backend.loadBrokenGraves()).thenReturn(List.of());when(h.plugin.getFeatureIO()).thenReturn(h.io);
            var ledger=mock(GraveClaimLedger.class);var decision=new CompletableFuture<GraveClaimLedger.Restoration>();when(ledger.restoration(any())).thenReturn(decision);when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            var restoration=new GraveRestoration(h.plugin,backend);when(h.plugin.getGraveRestoration()).thenReturn(restoration);
            h.regionWork.remove().run();FeaturePersistenceTest.await(h.io.barrier());verify(marker.block,never()).setType(Material.AIR);assertTrue(state.snapshot().containsKey(h.identity()));
            restoration.retry();FeaturePersistenceTest.await(h.io.barrier());assertTrue(restoration.hasPending(h.identity()));cleanup.onChunk(h.world,0,0);h.regionWork.remove().run();verify(marker.block,never()).setType(Material.AIR);
            when(h.config.isDisableArmorStands()).thenReturn(true);when(h.config.getGraveTimeLimit()).thenReturn("0");doAnswer(i->{h.active.add(i.getArgument(0));return null;}).when(h.plugin).restoreActiveRecord(any());
            decision.complete(GraveClaimLedger.Restoration.AVAILABLE);h.regionWork.remove().run();assertFalse(restoration.hasPending(h.identity()));
            cleanup.onChunk(h.world,0,0);h.regionWork.remove().run();FeaturePersistenceTest.await(h.io.barrier());verify(marker.block,never()).setType(Material.AIR);assertTrue(state.snapshot().isEmpty());restoration.retire();cleanup.retire();
        }
    }
    @Test void unavailableIdentityDoesNotStarveFollowingHealthyStartupRecord() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            h.active.clear();var marker=new Marker(h);when(h.config.isDisableArmorStands()).thenReturn(true);when(h.config.getGraveTimeLimit()).thenReturn("0");
            var healthy=new GravesConfig(h.owner.getUniqueId(),"Owner",new Location(h.world,1,64,1),new HashMap<>(),7,"",System.currentTimeMillis()+1,false,0,null,null,"PLAYER_HEAD");
            var ledger=mock(GraveClaimLedger.class);String bad=h.identity();when(ledger.restoration(any())).thenAnswer(i->CompletableFuture.completedFuture(bad.equals(i.getArgument(0))?GraveClaimLedger.Restoration.UNAVAILABLE:GraveClaimLedger.Restoration.AVAILABLE));when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            var backend=mock(GraveStorageManager.class);when(backend.loadGraves()).thenReturn(List.of(h.grave.getGravesConfig(),healthy));when(backend.loadBrokenGraves()).thenReturn(List.of());when(h.plugin.getFeatureIO()).thenReturn(h.io);doAnswer(i->{h.active.add(i.getArgument(0));return null;}).when(h.plugin).restoreActiveRecord(any());
            var restoration=new GraveRestoration(h.plugin,backend);restoration.retry();FeaturePersistenceTest.await(h.io.barrier());h.regionWork.remove().run();
            assertEquals(1,h.active.size());assertSame(healthy,h.active.getFirst().getGravesConfig());assertTrue(restoration.hasPending(bad));verify(backend).restorationResolved(healthy);restoration.retire();
        }
    }
    @Test void invalidStartupRemovalRemainsCancellableAndRepairable() throws Exception {
        for(boolean repair:List.of(false,true)) try(var h=new GraveActionsTest.Harness()) {
            h.active.clear();var marker=new Marker(h);when(marker.block.getType()).thenReturn(Material.STONE);when(h.config.isDisableArmorStands()).thenReturn(true);when(h.config.getGraveTimeLimit()).thenReturn("0");
            var state=new DurableFeatureState(h.io,new FeaturePersistenceTest.Disk(),8192);var cleanup=cleanup(h,state);
            java.util.concurrent.atomic.AtomicInteger observed=new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(i->{if(i.getArgument(0) instanceof com.bencodez.gravestonesplus.events.GraveRemoveEvent e){assertEquals(com.bencodez.gravestonesplus.events.GraveRemoveReason.INVALID,e.getReason());observed.incrementAndGet();if(repair)when(marker.block.getType()).thenReturn(Material.PLAYER_HEAD);else e.setCancelled(true);}return null;}).when(h.events).callEvent(any());
            var ledger=mock(GraveClaimLedger.class);when(ledger.restoration(any())).thenReturn(CompletableFuture.completedFuture(GraveClaimLedger.Restoration.AVAILABLE));when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            var backend=mock(GraveStorageManager.class);when(backend.loadGraves()).thenReturn(List.of(h.grave.getGravesConfig()));when(backend.loadBrokenGraves()).thenReturn(List.of());when(h.plugin.getFeatureIO()).thenReturn(h.io);doAnswer(i->{h.active.add(i.getArgument(0));return null;}).when(h.plugin).restoreActiveRecord(any());
            var restoration=new GraveRestoration(h.plugin,backend);restoration.retry();FeaturePersistenceTest.await(h.io.barrier());h.regionWork.remove().run();FeaturePersistenceTest.await(h.io.barrier());
            assertEquals(1,observed.get());assertFalse(h.grave.getGravesConfig().isDestroyed());assertTrue(state.snapshot().isEmpty());verify(h.plugin,never()).restoreBrokenRecord(any());assertEquals(repair?1:0,h.active.size());assertEquals(!repair,restoration.hasPending(h.identity()));restoration.retire();cleanup.retire();
        }
    }

    @Test void invalidStartupRemovalRevokesBothHelperKindsBeforeHistoricalPublication() throws Exception {
        for (boolean rejectRevocation : List.of(false, true)) try (var h = new GraveActionsTest.Harness()) {
            h.grant(TrustedGraveAccess.Kind.TRACK); h.grant(TrustedGraveAccess.Kind.RECOVER);
            h.active.clear(); var marker = new Marker(h); when(marker.block.getType()).thenReturn(Material.STONE);
            var cleanup = cleanup(h, new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192));
            var ledger = mock(GraveClaimLedger.class);
            when(ledger.restoration(any())).thenReturn(CompletableFuture.completedFuture(GraveClaimLedger.Restoration.AVAILABLE));
            when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            var backend = mock(GraveStorageManager.class);
            when(backend.loadGraves()).thenReturn(List.of(h.grave.getGravesConfig())); when(backend.loadBrokenGraves()).thenReturn(List.of());
            when(h.plugin.getFeatureIO()).thenReturn(h.io);
            CountDownLatch removed = new CountDownLatch(1);
            doAnswer(i -> { removed.countDown(); return null; }).when(backend).restorationResolved(any());
            doAnswer(i -> {
                assertFalse(h.access.allows(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK));
                assertFalse(h.access.allows(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER));
                return null;
            }).when(h.plugin).restoreBrokenRecord(any());
            if (rejectRevocation) h.accessDisk.fail = true;
            var restoration = new GraveRestoration(h.plugin, backend);
            restoration.retry(); FeaturePersistenceTest.await(h.io.barrier()); h.regionWork.remove().run();
            FeaturePersistenceTest.await(h.io.barrier()); FeaturePersistenceTest.await(h.io.barrier());
            assertFalse(h.access.allows(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK));
            assertFalse(h.access.allows(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER));
            GraveActionsTest.drainGround(h);
            if (!rejectRevocation) assertTrue(removed.await(2, TimeUnit.SECONDS), "durable revocation/removal callback must complete");
            assertEquals(!rejectRevocation, h.grave.getGravesConfig().isDestroyed());
            if (rejectRevocation) {
                assertTrue(restoration.hasPending(h.identity())); verify(h.plugin, never()).restoreBrokenRecord(any());
                verify(backend, never()).restorationResolved(any());
            } else {
                verify(h.plugin).restoreBrokenRecord(any()); verify(backend).restorationResolved(any());
                var restarted = new TrustedGraveAccessStore(new DurableFeatureState(h.io, h.accessDisk, 4096), System::currentTimeMillis);
                assertTrue(FeaturePersistenceTest.await(restarted.loaded()));
                assertFalse(restarted.allows(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER));
                assertTrue(restarted.list(h.identity()).isEmpty());
            }
            restoration.retire(); cleanup.retire();
        }
    }

    @Test void rejectedHistoricalCleanupDoesNotStarveHealthyFollowingRecord() throws Exception {
        try(var h=new GraveActionsTest.Harness()) {
            h.active.clear();var marker=new Marker(h);when(h.config.isDisableArmorStands()).thenReturn(true);when(h.config.getGraveTimeLimit()).thenReturn("0");
            h.grave.getGravesConfig().setDestroyed(true);
            var healthy=new GravesConfig(h.owner.getUniqueId(),"Owner",new Location(h.world,1,64,1),new HashMap<>(),7,"",System.currentTimeMillis()+1,false,0,null,null,"PLAYER_HEAD");
            var ledger=mock(GraveClaimLedger.class);when(ledger.restoration(any())).thenReturn(CompletableFuture.completedFuture(GraveClaimLedger.Restoration.AVAILABLE));when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            var cleanup=mock(GraveMarkerCleanup.class);when(cleanup.retain(any())).thenReturn(CompletableFuture.completedFuture(false));when(h.plugin.getMarkerCleanup()).thenReturn(cleanup);
            var backend=mock(GraveStorageManager.class);when(backend.loadGraves()).thenReturn(List.of(healthy));when(backend.loadBrokenGraves()).thenReturn(List.of(h.grave.getGravesConfig()));
            // Broken record first in the active load also covers paid records loaded by SQL.
            when(backend.loadGraves()).thenReturn(List.of(h.grave.getGravesConfig(),healthy));when(backend.loadBrokenGraves()).thenReturn(List.of());
            when(h.plugin.getFeatureIO()).thenReturn(h.io);doAnswer(i->{h.active.add(i.getArgument(0));return null;}).when(h.plugin).restoreActiveRecord(any());
            var restoration=new GraveRestoration(h.plugin,backend);restoration.retry();FeaturePersistenceTest.await(h.io.barrier());h.regionWork.remove().run();
            assertEquals(1,h.active.size());assertSame(healthy,h.active.getFirst().getGravesConfig());assertTrue(restoration.hasPending(h.identity()));
            verify(backend,never()).restorationResolved(h.grave.getGravesConfig());verify(h.plugin,never()).restoreBrokenRecord(any());restoration.retire();
        }
    }
    @Test void paidAndDestroyedMissingWorldRecordsStayPendingThenRebindBeforeHistoricalPublication() throws Exception {
        for(boolean paid:List.of(false,true)) try(var h=new GraveActionsTest.Harness()) {
            UUID missing=UUID.randomUUID();var record=h.grave.getGravesConfig();record.setWorldId(missing);record.getLocation().setWorld(null);record.setDestroyed(!paid);
            h.active.clear();when(h.config.getBrokenGraveTimeLimit()).thenReturn("7d");when(h.plugin.getFeatureIO()).thenReturn(h.io);
            when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of());var cleanup=cleanup(h,new DurableFeatureState(h.io,new FeaturePersistenceTest.Disk(),8192));
            var backend=mock(GraveStorageManager.class);when(backend.loadGraves()).thenReturn(List.of(record));when(backend.loadBrokenGraves()).thenReturn(List.of());
            var decisions=new LinkedBlockingQueue<CompletableFuture<GraveClaimLedger.Restoration>>();var ledger=mock(GraveClaimLedger.class);when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            when(ledger.restoration(any())).thenAnswer(i->{var decision=new CompletableFuture<GraveClaimLedger.Restoration>();decisions.add(decision);return decision;});
            var restoration=new GraveRestoration(h.plugin,backend);restoration.retry();
            var status=paid?GraveClaimLedger.Restoration.PAID:GraveClaimLedger.Restoration.AVAILABLE;
            var firstDecision=Objects.requireNonNull(decisions.poll(5,TimeUnit.SECONDS));
            FeaturePersistenceTest.await(h.io.barrier()); // Attach callback before completing on the mocked Bukkit owner.
            firstDecision.complete(status);
            assertTrue(restoration.hasPending(GraveIdentity.of(record)));assertEquals(missing.toString(),record.serialize().get("World"));assertEquals(17,record.getExp());
            verify(h.plugin,never()).restoreBrokenRecord(any());verify(backend,never()).restorationResolved(any());
            h.bukkit.when(()->Bukkit.getWorld(missing)).thenReturn(h.world);restoration.retry();
            Objects.requireNonNull(decisions.poll(5,TimeUnit.SECONDS)).complete(status);FeaturePersistenceTest.await(h.io.barrier());
            var restored=org.mockito.ArgumentCaptor.forClass(Grave.class);verify(h.plugin).restoreBrokenRecord(restored.capture());
            assertSame(h.world,restored.getValue().getGravesConfig().getLocation().getWorld());assertFalse(restoration.hasPending(GraveIdentity.of(record)));
            verify(h.world,never()).getChunkAt(anyInt(),anyInt());restoration.retire();cleanup.retire();
        }
    }
}
