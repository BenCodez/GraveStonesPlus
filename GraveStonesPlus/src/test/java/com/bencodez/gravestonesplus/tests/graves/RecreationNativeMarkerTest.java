package com.bencodez.gravestonesplus.tests.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.*;
import org.bukkit.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.*;
import com.bencodez.gravestonesplus.storage.*;
import com.bencodez.gravestonesplus.tests.storage.SqlJournalHarness;

class RecreationNativeMarkerTest {
    @TempDir Path directory;
    static final class Fixture implements AutoCloseable {
        final ClaimHarness h=new ClaimHarness();final GraveStorageBackend backend=mock(GraveStorageBackend.class);
        final GraveStorageManager manager;final SqlJournalHarness journal;final GraveStorageManager.RecreationAdmission admission;
        final Block block=mock(Block.class);final Chunk chunk=mock(Chunk.class);final Chest chest=mock(Chest.class);final Skull skull=mock(Skull.class);
        final BlockState ordinary=mock(BlockState.class);final Map<NamespacedKey,String> chunkValues=new HashMap<>(),tileValues=new HashMap<>(),entityValues=new HashMap<>();
        final AtomicReference<Material> material=new AtomicReference<>(Material.AIR);final Map<Material,BlockData> blockData=new EnumMap<>(Material.class);
        final ItemDisplay display=mock(ItemDisplay.class);final UUID displayId=UUID.randomUUID();final AtomicBoolean dead=new AtomicBoolean();
        final Map<String,org.bukkit.metadata.MetadataValue> metadata=new HashMap<>();
        final Object attempt;final Class<?> type;
        Fixture(Path path,GraveDisplayType style)throws Exception { this(path, style, true); }
        Fixture(Path path,GraveDisplayType style,boolean direct)throws Exception {
            manager=SqlRecreationOwnerTest.manager(h,backend);SqlRecreationOwnerTest.install(h,manager);
            journal=new SqlJournalHarness(h.io,backend,manager,path);
            h.grave.getGravesConfig().setGraveDisplayType(style.name());
            when(h.plugin.getConfigFile().isDisableArmorStands()).thenReturn(true);
            doCallRealMethod().when(h.plugin).recreateBrokenGrave(h.grave,null,true);
            doCallRealMethod().when(h.plugin).registerRecreationAttempt(any());
            doCallRealMethod().when(h.plugin).unregisterRecreationAttempt(any());
            doCallRealMethod().when(h.plugin).publishSqlRecreatedGrave(eq(h.grave),any(java.util.function.BooleanSupplier.class));
            doCallRealMethod().when(h.plugin).retireRecreationAttempts();
            admission=direct?manager.reserveRecreation(h.grave.getGravesConfig(),true):null;
            if(direct)assertNotNull(admission);
            for(var kind:List.of(Material.AIR,Material.STONE,Material.CHEST,Material.PLAYER_HEAD,Material.BARRIER)) {
                var data=mock(BlockData.class);when(data.getAsString()).thenReturn("minecraft:"+kind.name().toLowerCase(Locale.ROOT));when(data.getMaterial()).thenReturn(kind);when(data.clone()).thenReturn(data);blockData.put(kind,data);
            }
            var chunkData=pdc(chunkValues);var tileData=pdc(tileValues);var entityData=pdc(entityValues);
            var location=h.grave.getGravesConfig().getLocation();
            when(block.getWorld()).thenReturn(h.world);when(block.getLocation()).thenReturn(location.clone());when(block.getX()).thenReturn(location.getBlockX());when(block.getY()).thenReturn(location.getBlockY());when(block.getZ()).thenReturn(location.getBlockZ());
            when(block.getChunk()).thenReturn(chunk);when(chunk.getPersistentDataContainer()).thenReturn(chunkData);
            when(block.getMetadata("Grave")).thenReturn(List.of());when(block.getType()).thenAnswer(call->material.get());when(block.getBlockData()).thenAnswer(call->blockData.get(material.get()));
            when(block.getState()).thenAnswer(call->material.get()==Material.CHEST?chest:material.get()==Material.PLAYER_HEAD?skull:ordinary);
            var inventory=mock(Inventory.class);when(inventory.isEmpty()).thenReturn(true);when(chest.getBlockInventory()).thenReturn(inventory);
            when(chest.getPersistentDataContainer()).thenReturn(tileData);when(skull.getPersistentDataContainer()).thenReturn(tileData);when(chest.update(false,false)).thenReturn(true);when(skull.update(false,false)).thenReturn(true);
            doAnswer(call->{material.set(call.getArgument(0));tileValues.clear();return null;}).when(block).setType(any(),eq(false));
            doAnswer(call->{BlockData data=call.getArgument(0);material.set(data.getMaterial());tileValues.clear();return null;}).when(block).setBlockData(any(),eq(false));
            when(h.world.getBlockAt(any(Location.class))).thenReturn(block);when(h.world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(block);
            when(h.world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);
            when(h.plugin.getGraveDisplayEntityHandler()).thenReturn(new GraveDisplayEntityHandle(h.plugin));when(h.world.spawn(any(Location.class),eq(ItemDisplay.class))).thenReturn(display);
            when(display.isDead()).thenAnswer(call->dead.get());when(display.isValid()).thenReturn(true);when(display.getWorld()).thenReturn(h.world);when(display.getLocation()).thenReturn(location.clone().add(.5,.5,.5));when(display.getUniqueId()).thenReturn(displayId);when(display.getPersistentDataContainer()).thenReturn(entityData);
            doAnswer(call->{dead.set(true);return null;}).when(display).remove();
            doAnswer(call->{metadata.put(call.getArgument(0),call.getArgument(1));return null;}).when(display).setMetadata(anyString(),any());
            doAnswer(call->{metadata.remove(call.getArgument(0));return null;}).when(display).removeMetadata(anyString(),eq(h.plugin));
            when(display.getMetadata(anyString())).thenAnswer(call->metadata.containsKey(call.getArgument(0))?List.of(metadata.get(call.getArgument(0))):List.of());
            type=Class.forName("com.bencodez.gravestonesplus.graves.RecreationNativeMarker");var constructor=type.getDeclaredConstructor(GraveStonesPlus.class,Grave.class,GraveStorageManager.RecreationAdmission.class,GraveDisplayType.class);constructor.setAccessible(true);attempt=direct?constructor.newInstance(h.plugin,h.grave,admission,style):null;
        }
        static PersistentDataContainer pdc(Map<NamespacedKey,String> values) {
            var data=mock(PersistentDataContainer.class);when(data.get(any(),eq(PersistentDataType.STRING))).thenAnswer(call->values.get(call.getArgument(0)));
            doAnswer(call->{values.put(call.getArgument(0),call.getArgument(2));return null;}).when(data).set(any(),eq(PersistentDataType.STRING),anyString());
            doAnswer(call->{values.remove(call.getArgument(0));return null;}).when(data).remove(any());when(data.getKeys()).thenAnswer(call->new HashSet<>(values.keySet()));return data;
        }
        Object invoke(String method)throws Exception {
            var action=type.getDeclaredMethod(method);action.setAccessible(true);
            try{return action.invoke(attempt);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception e)throw e;if(failure.getCause() instanceof Error e)throw e;throw failure;}
        }
        @SuppressWarnings("unchecked") CompletableFuture<Boolean> future(String method)throws Exception {return (CompletableFuture<Boolean>)invoke(method);}
        public void close()throws Exception {try{if(attempt!=null)invoke("retire");else h.grave.retireAcknowledgments();journal.close();}finally{h.close();}}
    }
    @ParameterizedTest @EnumSource(value=GraveDisplayType.class,names={"CHEST","PLAYER_HEAD"})
    void cancelledBlockPlacementRestoresOnlyOwnedNativeStateAndReleasesAfterAck(GraveDisplayType style)throws Exception {
        try(var f=new Fixture(directory,style)) {
            assertTrue((Boolean)f.invoke("createOnLocation"));assertTrue(f.h.grave.getGravesConfig().isDestroyed());assertTrue(f.h.active.isEmpty());
            verify(f.block,never()).setMetadata(anyString(),any());assertTrue(PendingRecreationMarker.protects(f.h.plugin,f.block));
            f.invoke("retire");assertTrue(ClaimHarness.await(f.future("rollbackOnLocation")));assertEquals(Material.AIR,f.material.get());
            assertTrue(f.manager.isRecreationPending(f.h.identity()));assertTrue(f.admission.finish());assertFalse(f.manager.isRecreationPending(f.h.identity()));
            assertFalse(f.h.io.hasUnsubmittedAdmissions());verify(f.backend,never()).saveRecreatedGrave(any());assertEquals(17,f.h.grave.getGravesConfig().getExp());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void capturedPartialDisplayInitializationNeedsBothOwnerCleanupAcknowledgments(boolean error)throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.DISPLAY_ENTITY);var builders=mockConstruction(ItemBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->when(builder.toItemStack()).thenReturn(mock(ItemStack.class)))) {
            if(error)doThrow(new AssertionError("synthetic native initialization error")).when(f.display).setItemStack(any());
            else doThrow(new IllegalStateException("synthetic native initialization failure")).when(f.display).setItemStack(any());
            if(error)assertThrows(AssertionError.class,()->f.invoke("createOnLocation"));else assertThrows(IllegalStateException.class,()->f.invoke("createOnLocation"));
            f.invoke("retire");var cleanup=f.future("rollbackOnLocation");assertEquals(Material.AIR,f.material.get());assertFalse(cleanup.isDone());verify(f.display,never()).remove();verify(f.display,never()).getPersistentDataContainer();
            var duplicate=f.future("rollbackOnLocation");assertFalse(duplicate.isDone());assertEquals(1,f.h.entityWork.size());
            assertTrue(f.manager.isRecreationPending(f.h.identity()));f.h.entityWork.remove().run();assertTrue(ClaimHarness.await(cleanup));assertTrue(ClaimHarness.await(duplicate));verify(f.display,times(1)).remove();
            assertTrue(f.admission.finish());assertFalse(f.h.io.hasUnsubmittedAdmissions());verify(f.backend,never()).saveRecreatedGrave(any());
        }
    }
    @Test void uncertainBlockMutationAndForeignEditsAreNeverForceRestored()throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.CHEST)) {
            doAnswer(call->{f.material.set(Material.CHEST);throw new IllegalStateException("synthetic partial block mutation");}).when(f.block).setType(Material.CHEST,false);
            assertThrows(IllegalStateException.class,()->f.invoke("createOnLocation"));f.invoke("retire");assertFalse(ClaimHarness.await(f.future("rollbackOnLocation")));
            verify(f.block,never()).setBlockData(any(),anyBoolean());assertTrue(f.manager.isRecreationPending(f.h.identity()));assertFalse(f.h.io.hasUnsubmittedAdmissions());
        }
    }
    @Test void consumedHeldSqlNeverAuthorizesNativeRollback()throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.CHEST)) {
            assertTrue((Boolean)f.invoke("createOnLocation"));doThrow(new IllegalStateException("synthetic SQL outage")).when(f.backend).saveRecreatedGrave(any());
            assertThrows(ExecutionException.class,()->ClaimHarness.await(f.future("commitSql")));f.invoke("retire");assertFalse(ClaimHarness.await(f.future("rollbackOnLocation")));
            verify(f.block,never()).setBlockData(any(),anyBoolean());assertEquals(Material.CHEST,f.material.get());assertTrue(f.h.grave.getGravesConfig().isDestroyed());assertEquals(1,f.journal.records());
        }
    }
    @Test void unrelatedSolidPlacementIsRejectedWithoutNativeMutation()throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.PLAYER_HEAD)) {
            f.material.set(Material.STONE);assertFalse((Boolean)f.invoke("createOnLocation"));f.invoke("retire");assertTrue(ClaimHarness.await(f.future("rollbackOnLocation")));
            verify(f.block,never()).setType(any(),anyBoolean());assertEquals(Material.STONE,f.material.get());assertTrue(f.admission.finish());
        }
    }
    @Test void acknowledgedDisplaySqlContainsFreshIdsWithoutPublishingHistoricalObject()throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.DISPLAY_ENTITY);var builders=mockConstruction(ItemBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->when(builder.toItemStack()).thenReturn(mock(ItemStack.class)))) {
            UUID oldDisplay=UUID.randomUUID(),oldInteraction=UUID.randomUUID();f.h.grave.getGravesConfig().setDisplayUUID(oldDisplay);f.h.grave.getGravesConfig().setInteractUUID(oldInteraction);
            assertTrue((Boolean)f.invoke("createOnLocation"));assertTrue(ClaimHarness.await(f.future("commitSql")));assertTrue(ClaimHarness.await(f.future("commitSql")));
            var captured=org.mockito.ArgumentCaptor.forClass(GravesConfig.class);verify(f.backend,times(1)).saveRecreatedGrave(captured.capture());
            assertEquals(f.displayId,captured.getValue().getDisplayUUID());assertNull(captured.getValue().getInteractUUID());assertEquals("DISPLAY_ENTITY",captured.getValue().getGraveDisplayType());
            assertEquals(0,captured.getValue().getExp());assertTrue(captured.getValue().getItems().isEmpty());
            assertEquals(oldDisplay,f.h.grave.getGravesConfig().getDisplayUUID());assertEquals(oldInteraction,f.h.grave.getGravesConfig().getInteractUUID());assertTrue(f.h.grave.getGravesConfig().isDestroyed());assertTrue(f.h.active.isEmpty());
            assertFalse(ClaimHarness.await(f.future("rollbackOnLocation")));assertTrue(f.manager.isRecreationPending(f.h.identity()));
            verify(f.display,never()).setMetadata(anyString(),any());verify(f.display,never()).remove();verify(f.h.owner,never()).giveExp(anyInt());
        }
    }
    @Test void retiredConfirmedSqlMissAllowsOwnedRollbackButNotAnEarlyFenceRelease()throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.PLAYER_HEAD)) {
            assertTrue((Boolean)f.invoke("createOnLocation"));doThrow(new SqlConditionNotMet()).when(f.backend).saveRecreatedGrave(any());
            assertFalse(ClaimHarness.await(f.future("commitSql")));assertEquals(0,f.journal.records());assertFalse(f.admission.isCancelledBeforeCommit());
            assertTrue(f.manager.isRecreationPending(f.h.identity()));assertTrue(ClaimHarness.await(f.future("rollbackOnLocation")));assertEquals(Material.AIR,f.material.get());
            assertTrue(f.manager.isRecreationPending(f.h.identity()));assertTrue(f.admission.finish());assertTrue(f.h.grave.getGravesConfig().isDestroyed());
        }
    }
    @ParameterizedTest @ValueSource(strings={"attempt","material","container","metadata","runtime"})
    void unrelatedNativeEditsCannotBeOverwrittenByRollback(String change)throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.CHEST)) {
            assertTrue((Boolean)f.invoke("createOnLocation"));
            if(change.equals("attempt"))f.chunkValues.put(PendingRecreationMarker.tokenKey(f.h.plugin,f.block),UUID.randomUUID().toString());
            if(change.equals("material"))f.material.set(Material.STONE);
            if(change.equals("container"))when(f.chest.getBlockInventory().isEmpty()).thenReturn(false);
            if(change.equals("metadata"))f.tileValues.put(new NamespacedKey("synthetic","foreign"),"synthetic");
            if(change.equals("runtime"))when(f.h.plugin.getStorageManager()).thenReturn(mock(GraveStorageManager.class));
            f.invoke("retire");assertFalse(ClaimHarness.await(f.future("rollbackOnLocation")));verify(f.block,never()).setBlockData(any(),anyBoolean());assertFalse(f.h.io.hasUnsubmittedAdmissions());
        }
    }
    @Test void aLiveGravesAdoptedDisplayIsNeverRemovedByPartialSpawnCleanup()throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.DISPLAY_ENTITY);var builders=mockConstruction(ItemBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->when(builder.toItemStack()).thenReturn(mock(ItemStack.class)))) {
            doThrow(new IllegalStateException("synthetic initialization failure")).when(f.display).setItemStack(any());assertThrows(IllegalStateException.class,()->f.invoke("createOnLocation"));
            var live=mock(Grave.class);var record=new GravesConfig(UUID.randomUUID(),"Synthetic",f.h.grave.getGravesConfig().getLocation(),new HashMap<>(),0,"",2000,false,0,f.displayId,null,"DISPLAY_ENTITY");
            when(live.getGravesConfig()).thenReturn(record);when(live.getItemDisplay()).thenReturn(f.display);f.h.active.add(live);
            f.invoke("retire");var cleaned=f.future("rollbackOnLocation");assertFalse(cleaned.isDone());f.h.entityWork.remove().run();assertFalse(ClaimHarness.await(cleaned));verify(f.display,never()).remove();
        }
    }

    @Test void reentrantShutdownInsideNativeMutationDoesNotWaitForItsOwnActor()throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.CHEST)) {
            doAnswer(call->{f.material.set(Material.CHEST);f.manager.close();f.h.io.close();return null;}).when(f.block).setType(Material.CHEST,false);
            assertThrows(CancellationException.class,()->f.invoke("createOnLocation"));
            assertTrue(f.h.io.isTerminated());assertTrue(f.admission.isCancelledBeforeCommit());assertFalse(ClaimHarness.await(f.future("commitSql")));
            assertTrue(f.h.grave.getGravesConfig().isDestroyed());assertTrue(f.manager.isRecreationPending(f.h.identity()));verify(f.backend,never()).saveRecreatedGrave(any());
        }
    }
    @Test void nativeBodiesRejectALifecycleMonitorHeldAcrossTheirBoundary()throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.CHEST)) {
            synchronized(f.h.grave){assertThrows(IllegalStateException.class,()->f.invoke("createOnLocation"));}
            verify(f.block,never()).setType(any(),anyBoolean());f.invoke("retire");
            synchronized(f.h.grave){assertFalse(ClaimHarness.await(f.future("rollbackOnLocation")));}
            assertTrue(ClaimHarness.await(f.future("rollbackOnLocation")));assertTrue(f.admission.finish());
        }
    }

    @ParameterizedTest @ValueSource(strings={"ready","moved","identity","retired","rejected"})
    void displayAdoptionRequiresItsOwnAcknowledgedOwnerAndUsableFootprint(String condition)throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.DISPLAY_ENTITY);var builders=mockConstruction(ItemBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->when(builder.toItemStack()).thenReturn(mock(ItemStack.class)))) {
            assertTrue((Boolean)f.invoke("createOnLocation"));assertTrue(ClaimHarness.await(f.future("commitSql")));
            if(condition.equals("moved"))when(f.display.getLocation()).thenReturn(new Location(f.h.world,100,64,100));
            if(condition.equals("identity"))when(f.display.getUniqueId()).thenReturn(UUID.randomUUID());
            if(condition.equals("retired"))f.invoke("retire");
            if(condition.equals("rejected")) {
                var platform=f.h.plugin.getBukkitScheduler().getFoliaLib().getImpl();
                doThrow(new RejectedExecutionException("synthetic entity owner retirement")).when(platform).runAtEntityWithFallback(any(),any(),any(Runnable.class));
            }
            var adopted=f.future("adoptDisplayOnOwner");
            if(!condition.equals("retired")&&!condition.equals("rejected")) {assertFalse(adopted.isDone());assertFalse((Boolean)f.invoke("hasAdoptionAcknowledgment"));f.h.entityWork.remove().run();}
            assertEquals(condition.equals("ready"),ClaimHarness.await(adopted));assertEquals(condition.equals("ready"),(Boolean)f.invoke("hasAdoptionAcknowledgment"));
            verify(f.display,times(condition.equals("ready")?1:0)).setMetadata(eq("Grave"),any());
            assertTrue(f.h.grave.getGravesConfig().isDestroyed());assertTrue(f.manager.isRecreationPending(f.h.identity()));
        }
    }
    @Test void retirementAfterEntityAckRevokesEvidenceAndProvisionalMetadataOnItsOwner()throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.DISPLAY_ENTITY);var builders=mockConstruction(ItemBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->when(builder.toItemStack()).thenReturn(mock(ItemStack.class)))) {
            assertTrue((Boolean)f.invoke("createOnLocation"));assertTrue(ClaimHarness.await(f.future("commitSql")));
            var adopted=f.future("adoptDisplayOnOwner");f.h.entityWork.remove().run();assertTrue(ClaimHarness.await(adopted));
            when(f.display.getMetadata("Grave")).thenReturn(List.of(new org.bukkit.metadata.FixedMetadataValue(f.h.plugin,f.h.grave)));
            f.invoke("retire");assertFalse((Boolean)f.invoke("hasAdoptionAcknowledgment"));
            var revoked=f.future("revokeDisplayOnOwner");assertFalse(revoked.isDone());verify(f.display,never()).removeMetadata(anyString(),any());
            f.h.entityWork.remove().run();assertTrue(ClaimHarness.await(revoked));verify(f.display).removeMetadata("Grave",f.h.plugin);verify(f.display,never()).remove();
            assertTrue(f.manager.isRecreationPending(f.h.identity()));assertTrue(f.h.grave.getGravesConfig().isDestroyed());
        }
    }

    @Test void anUnrelatedHeadProfileIsNotDestroyedByRollback()throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.PLAYER_HEAD)) {
            assertTrue((Boolean)f.invoke("createOnLocation"));var changed=mock(OfflinePlayer.class);when(changed.getUniqueId()).thenReturn(UUID.randomUUID());when(f.skull.getOwningPlayer()).thenReturn(changed);
            f.invoke("retire");assertFalse(ClaimHarness.await(f.future("rollbackOnLocation")));verify(f.block,never()).setBlockData(any(),anyBoolean());
        }
    }
    @Test void aMovedPartialDisplayIsHeldRatherThanDeletedInAnUnrelatedFootprint()throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.DISPLAY_ENTITY);var builders=mockConstruction(ItemBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->when(builder.toItemStack()).thenReturn(mock(ItemStack.class)))) {
            doThrow(new IllegalStateException("synthetic initialization failure")).when(f.display).setItemStack(any());assertThrows(IllegalStateException.class,()->f.invoke("createOnLocation"));
            when(f.display.getLocation()).thenReturn(new Location(f.h.world,100,64,100));f.invoke("retire");var cleanup=f.future("rollbackOnLocation");f.h.entityWork.remove().run();assertFalse(ClaimHarness.await(cleanup));verify(f.display,never()).remove();
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void partialClaimMetadataAdoptionFailureCanBeRevokedWithoutRemovingDurableMarker(boolean error)throws Exception {
        try(var f=new Fixture(directory,GraveDisplayType.DISPLAY_ENTITY);var builders=mockConstruction(ItemBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->when(builder.toItemStack()).thenReturn(mock(ItemStack.class)))) {
            assertTrue((Boolean)f.invoke("createOnLocation"));assertTrue(ClaimHarness.await(f.future("commitSql")));
            doAnswer(call->{when(f.display.getMetadata("Grave")).thenReturn(List.of(new org.bukkit.metadata.FixedMetadataValue(f.h.plugin,f.h.grave)));if(error)throw new AssertionError("synthetic metadata publication error");throw new IllegalStateException("synthetic metadata publication failure");}).when(f.display).setMetadata(eq("Grave"),any());
            var adopted=f.future("adoptDisplayOnOwner");f.h.entityWork.remove().run();assertFalse(ClaimHarness.await(adopted));assertFalse((Boolean)f.invoke("hasAdoptionAcknowledgment"));
            var revoked=f.future("revokeDisplayOnOwner");var duplicate=f.future("revokeDisplayOnOwner");assertEquals(1,f.h.entityWork.size());f.h.entityWork.remove().run();assertTrue(ClaimHarness.await(revoked));assertTrue(ClaimHarness.await(duplicate));
            verify(f.display,times(1)).removeMetadata("Grave",f.h.plugin);verify(f.display,never()).remove();assertTrue(f.manager.isRecreationPending(f.h.identity()));assertTrue(f.h.grave.getGravesConfig().isDestroyed());
        }
    }

    @ParameterizedTest @EnumSource(value=GraveDisplayType.class,names={"CHEST","PLAYER_HEAD"})
    void sameMaterialHistoricalMarkerPreservesExistingNativeScalars(GraveDisplayType style)throws Exception {
        try(var f=new Fixture(directory,style)) {
            f.material.set(style==GraveDisplayType.CHEST?Material.CHEST:Material.PLAYER_HEAD);
            GraveMarkerCleanup.tag(f.h.plugin,f.block,f.h.identity());
            when(f.chest.getCustomName()).thenReturn("Existing marker");
            var player=mock(org.bukkit.OfflinePlayer.class);when(player.getUniqueId()).thenReturn(UUID.randomUUID());when(f.skull.getOwningPlayer()).thenReturn(player);
            assertTrue((Boolean)f.invoke("createOnLocation"));f.invoke("retire");assertTrue(ClaimHarness.await(f.future("rollbackOnLocation")));
            verify(f.chest,never()).setCustomName(any());verify(f.skull,never()).setOwningPlayer(any());
            verify(f.block,never()).setBlockData(any(),anyBoolean());assertTrue(f.admission.finish());
        }
    }

}
