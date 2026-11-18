package com.bencodez.gravestonesplus.tests.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.*;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.gravestonesplus.graves.*;
import com.bencodez.gravestonesplus.storage.*;

class SqlPhysicalRecreationIntegrationTest {
    @TempDir Path directory;
    static void pump(ClaimHarness h,CompletableFuture<?> result)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!result.isDone()&&System.nanoTime()<end){h.drain();while(!h.regionWork.isEmpty())h.regionWork.remove().run();Thread.sleep(1);}
        h.drain();
    }
    static void awaitPublication(ClaimHarness h)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(h.regionWork.isEmpty()&&System.nanoTime()<end){h.drain();Thread.sleep(1);}
        assertFalse(h.regionWork.isEmpty());
    }
    @ParameterizedTest @EnumSource(GraveDisplayType.class)
    void realEntryPointRequiresSqlAndBothOwnersWithoutASecondWrite(GraveDisplayType style)throws Exception {
        try(var f=new RecreationNativeMarkerTest.Fixture(directory,style,false);
                var builders=mockConstruction(ItemBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->when(builder.toItemStack()).thenReturn(mock(ItemStack.class)))) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            doAnswer(call->{assertTrue(f.h.io.isOwnerThread());entered.countDown();assertTrue(release.await(4,TimeUnit.SECONDS));return null;}).when(f.backend).saveRecreatedGrave(any());
            var result=f.h.plugin.recreateBrokenGrave(f.h.grave,null,true);SqlRecreationOwnerTest.runRegion(f.h);
            try {
                assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(f.h.grave.getGravesConfig().isDestroyed());assertTrue(f.h.active.isEmpty());
                assertTrue(f.manager.isRecreationPending(f.h.identity()));assertFalse(result.isDone());
                verify(f.block,never()).setMetadata(anyString(),any());verify(f.display,never()).setMetadata(anyString(),any());
            }finally{release.countDown();}
            pump(f.h,result);assertTrue(ClaimHarness.await(result));assertFalse(f.h.grave.getGravesConfig().isDestroyed());
            assertEquals(List.of(f.h.grave),f.h.active);assertTrue(f.h.plugin.brokenGraveSnapshot().isEmpty());
            assertFalse(f.manager.isRecreationPending(f.h.identity()));assertEquals(0,f.journal.records());
            assertEquals(style.name(),f.h.grave.getGravesConfig().getGraveDisplayType());assertNull(f.h.grave.getGravesConfig().getInteractUUID());
            assertEquals(style==GraveDisplayType.DISPLAY_ENTITY?f.displayId:null,f.h.grave.getGravesConfig().getDisplayUUID());
            assertEquals(17,f.h.grave.getGravesConfig().getExp());assertFalse(f.h.grave.getGravesConfig().getItems().isEmpty());
            assertFalse(PendingRecreationMarker.hasAttemptTag(f.h.plugin,f.block));
            verify(f.backend,times(1)).saveRecreatedGrave(any());verify(f.backend,never()).upsertGrave(any(),anyBoolean());verify(f.h.owner,never()).giveExp(anyInt());
        }
    }
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void nativeFailureMissAndOutageHaveDistinctCleanupAuthority(int mode)throws Exception {
        try(var f=new RecreationNativeMarkerTest.Fixture(directory,GraveDisplayType.CHEST,false)) {
            if(mode==0)doAnswer(call->{f.material.set(Material.CHEST);throw new IllegalStateException("synthetic partial native write");}).when(f.block).setType(Material.CHEST,false);
            if(mode==1)doThrow(new SqlConditionNotMet()).when(f.backend).saveRecreatedGrave(any());
            if(mode==2)doThrow(new IllegalStateException("synthetic SQL outage")).when(f.backend).saveRecreatedGrave(any());
            var result=f.h.plugin.recreateBrokenGrave(f.h.grave,null,true);pump(f.h,result);
            assertFalse(ClaimHarness.await(result));assertTrue(f.h.grave.getGravesConfig().isDestroyed());assertTrue(f.h.active.isEmpty());
            if(mode==1){assertEquals(Material.AIR,f.material.get());assertFalse(f.manager.isRecreationPending(f.h.identity()));}
            else assertTrue(f.manager.isRecreationPending(f.h.identity()));
            assertEquals(mode==2?1:0,f.journal.records());verify(f.h.owner,never()).giveExp(anyInt());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void lifecycleRetirementInsideNativeMutationReleasesCapacityBeforeWorkerClose(boolean outsideLists)throws Exception {
        try(var f=new RecreationNativeMarkerTest.Fixture(directory,GraveDisplayType.CHEST,false)) {
            if(outsideLists)SqlRecreationOwnerTest.field(f.h.plugin,"brokenGraves",new ArrayList<Grave>());
            doAnswer(call->{
                f.material.set(Material.CHEST);
                if(outsideLists)f.h.plugin.retireRecreationAttempts();else f.h.grave.retireAcknowledgments();
                f.manager.close();f.h.io.close();assertFalse(f.h.io.hasUnsubmittedAdmissions());return null;
            }).when(f.block).setType(Material.CHEST,false);
            var result=f.h.plugin.recreateBrokenGrave(f.h.grave,null,true);SqlRecreationOwnerTest.runRegion(f.h);
            assertFalse(ClaimHarness.await(result));assertTrue(f.h.grave.getGravesConfig().isDestroyed());assertTrue(f.h.active.isEmpty());
            verify(f.backend,never()).saveRecreatedGrave(any());
        }
    }
    @Test void canceledCallerObservationDoesNotCancelTheNativeSqlOwner()throws Exception {
        try(var f=new RecreationNativeMarkerTest.Fixture(directory,GraveDisplayType.CHEST,false)) {
            var result=f.h.plugin.recreateBrokenGrave(f.h.grave,null,true);assertTrue(result.cancel(false));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(f.h.grave.getGravesConfig().isDestroyed()&&System.nanoTime()<end){f.h.drain();while(!f.h.regionWork.isEmpty())f.h.regionWork.remove().run();Thread.sleep(1);}
            assertFalse(f.h.grave.getGravesConfig().isDestroyed());assertFalse(f.manager.isRecreationPending(f.h.identity()));verify(f.backend,times(1)).saveRecreatedGrave(any());
        }
    }
    @Test void retirementAfterEntityAckRevokesWithoutDeletingSqlRecoverableMarker()throws Exception {
        try(var f=new RecreationNativeMarkerTest.Fixture(directory,GraveDisplayType.DISPLAY_ENTITY,false);
                var builders=mockConstruction(ItemBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->when(builder.toItemStack()).thenReturn(mock(ItemStack.class)))) {
            var result=f.h.plugin.recreateBrokenGrave(f.h.grave,null,true);SqlRecreationOwnerTest.runRegion(f.h);awaitPublication(f.h);
            verify(f.display).setMetadata(eq("Grave"),any());assertTrue(f.metadata.containsKey("Grave"));assertFalse(result.isDone());assertFalse(f.h.regionWork.isEmpty());
            f.h.grave.retireAcknowledgments();pump(f.h,result);
            assertFalse(ClaimHarness.await(result));assertTrue(f.h.grave.getGravesConfig().isDestroyed());assertTrue(f.manager.isRecreationPending(f.h.identity()));
            verify(f.display,never()).remove();assertFalse(f.metadata.containsKey("Grave"));verify(f.display).removeMetadata("Grave",f.h.plugin);assertEquals(Material.BARRIER,f.material.get());verify(f.backend,times(1)).saveRecreatedGrave(any());
        }
    }
    @Test void ownerPublicationRejectionRetainsAnAcknowledgedSqlFence()throws Exception {
        try(var f=new RecreationNativeMarkerTest.Fixture(directory,GraveDisplayType.CHEST,false)) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            doAnswer(call->{entered.countDown();assertTrue(release.await(4,TimeUnit.SECONDS));return null;}).when(f.backend).saveRecreatedGrave(any());
            var result=f.h.plugin.recreateBrokenGrave(f.h.grave,null,true);SqlRecreationOwnerTest.runRegion(f.h);
            try{assertTrue(entered.await(3,TimeUnit.SECONDS));var platform=f.h.plugin.getBukkitScheduler().getFoliaLib().getImpl();
                doThrow(new RejectedExecutionException("synthetic location retirement")).when(platform).runAtLocation(any(Location.class),any());}finally{release.countDown();}
            f.h.drain();assertFalse(ClaimHarness.await(result));assertTrue(f.h.grave.getGravesConfig().isDestroyed());assertTrue(f.manager.isRecreationPending(f.h.identity()));
            assertEquals(Material.CHEST,f.material.get());assertEquals(0,f.journal.records());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void retirementOrFailureInNativePublicationNeverExposesHistoricalRewards(boolean retire)throws Exception {
        try(var f=new RecreationNativeMarkerTest.Fixture(directory,GraveDisplayType.CHEST,false)) {
            doAnswer(call->{if(retire)f.h.grave.retireAcknowledgments();else throw new IllegalStateException("synthetic marker metadata failure");return null;})
                .when(f.block).setMetadata(eq("Grave"),any());
            var result=f.h.plugin.recreateBrokenGrave(f.h.grave,null,true);pump(f.h,result);
            assertFalse(ClaimHarness.await(result));assertTrue(f.h.grave.getGravesConfig().isDestroyed());assertTrue(f.h.active.isEmpty());
            assertTrue(f.manager.isRecreationPending(f.h.identity()));assertEquals(Material.CHEST,f.material.get());assertEquals(0,f.journal.records());
            verify(f.backend,times(1)).saveRecreatedGrave(any());verify(f.h.owner,never()).giveExp(anyInt());
        }
    }
    @Test void failedListPublicationRestoresBothListsAndConfig()throws Exception {
        try(var f=new RecreationNativeMarkerTest.Fixture(directory,GraveDisplayType.CHEST,false)) {
            var broken=new ArrayList<Grave>(List.of(f.h.grave)) {
                boolean fail=true;
                @Override public boolean removeIf(java.util.function.Predicate<? super Grave> predicate) {
                    if(fail){fail=false;throw new IllegalStateException("synthetic list publication failure");}return super.removeIf(predicate);
                }
            };
            SqlRecreationOwnerTest.field(f.h.plugin,"brokenGraves",broken);
            UUID legacyDisplay=UUID.randomUUID(),legacyInteract=UUID.randomUUID();
            f.h.grave.getGravesConfig().setDisplayUUID(legacyDisplay);f.h.grave.getGravesConfig().setInteractUUID(legacyInteract);
            var result=f.h.plugin.recreateBrokenGrave(f.h.grave,null,true);pump(f.h,result);
            assertFalse(ClaimHarness.await(result));assertTrue(f.h.grave.getGravesConfig().isDestroyed());assertEquals(2000,f.h.grave.getGravesConfig().getDestroyedTime());
            assertEquals(legacyDisplay,f.h.grave.getGravesConfig().getDisplayUUID());assertEquals(legacyInteract,f.h.grave.getGravesConfig().getInteractUUID());
            assertTrue(f.h.active.isEmpty());assertEquals(List.of(f.h.grave),broken);assertTrue(f.manager.isRecreationPending(f.h.identity()));
            assertTrue(PendingRecreationMarker.protects(f.h.plugin,f.block),"failed publication retains exact provisional protection");
        }
    }
    @Test void realCapacityLimitRejectsBeforeAnyNativeMutation()throws Exception {
        try(var f=new RecreationNativeMarkerTest.Fixture(directory,GraveDisplayType.CHEST,false)) {
            // Finish durable permission checks first, then saturate the actual native admission boundary.
            var result=f.h.plugin.recreateBrokenGrave(f.h.grave,null,true);f.h.drain();assertEquals(1,f.h.regionWork.size());
            var reserved=new ArrayList<GraveStorageManager.RecreationAdmission>();
            try {
                for(int i=0;i<128;i++) {
                    var record=new GravesConfig(UUID.randomUUID(),"Synthetic",new Location(f.h.world,i+10,64,10),new HashMap<>(),0,"",1000+i,true,2000,null,null,"CHEST");
                    var slot=f.manager.reserveRecreation(record,true);assertNotNull(slot);reserved.add(slot);
                }
                f.h.regionWork.remove().run();assertFalse(ClaimHarness.await(result));
                verify(f.block,never()).setType(any(),anyBoolean());verify(f.h.world,never()).spawn(any(Location.class),eq(ItemDisplay.class));
                assertTrue(f.h.grave.getGravesConfig().isDestroyed());verify(f.backend,never()).saveRecreatedGrave(any());
            }finally{reserved.forEach(GraveStorageManager.RecreationAdmission::close);}
        }
    }
    @Test void durableHeldNativeMetadataReplaysWithoutRecreatingOrSerializingContents()throws Exception {
        Path replayPath;
        try(var f=new RecreationNativeMarkerTest.Fixture(directory,GraveDisplayType.CHEST,false)) {
            replayPath=f.journal.directory;
            doThrow(new IllegalStateException("synthetic persistent SQL outage")).when(f.backend).saveRecreatedGrave(any());
            var result=f.h.plugin.recreateBrokenGrave(f.h.grave,null,true);pump(f.h,result);assertFalse(ClaimHarness.await(result));
            assertEquals(1,f.journal.records());assertEquals(Material.CHEST,f.material.get());assertTrue(f.h.grave.getGravesConfig().isDestroyed());
        }
        try(var io=new FeatureIO()) {
            var backend=mock(GraveStorageBackend.class);
            var journal=new SqlRecoveryJournal(io,backend,replayPath,ignored->{});
            try {
                assertTrue(journal.loaded().get(3,TimeUnit.SECONDS));io.barrier().get(3,TimeUnit.SECONDS);
                var capture=org.mockito.ArgumentCaptor.forClass(GravesConfig.class);verify(backend).saveRecreatedGrave(capture.capture());
                var saved=capture.getValue();assertEquals("CHEST",saved.getGraveDisplayType());assertNull(saved.getDisplayUUID());assertNull(saved.getInteractUUID());
                assertFalse(saved.isDestroyed());verify(backend,never()).upsertGrave(any(),anyBoolean());
            } finally { journal.close(); }
        }
    }

    @Test void reentrantRetirementDuringListPublicationRollsBackBeforeFenceRelease()throws Exception {
        try(var f=new RecreationNativeMarkerTest.Fixture(directory,GraveDisplayType.CHEST,false)) {
            var broken=new ArrayList<Grave>(List.of(f.h.grave)) {
                boolean first=true;
                @Override public boolean removeIf(java.util.function.Predicate<? super Grave> predicate) {
                    if(first){first=false;f.h.grave.retireAcknowledgments();}return super.removeIf(predicate);
                }
            };
            SqlRecreationOwnerTest.field(f.h.plugin,"brokenGraves",broken);
            var result=f.h.plugin.recreateBrokenGrave(f.h.grave,null,true);pump(f.h,result);
            assertFalse(ClaimHarness.await(result));assertTrue(f.h.grave.getGravesConfig().isDestroyed());assertTrue(f.h.active.isEmpty());
            assertEquals(List.of(f.h.grave),broken);assertTrue(f.manager.isRecreationPending(f.h.identity()));
            assertTrue(PendingRecreationMarker.protects(f.h.plugin,f.block));assertEquals(0,f.journal.records());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void partialDisplayInitializationRequiresBothOwnersBeforeUnusedFenceRelease(boolean error)throws Exception {
        try(var f=new RecreationNativeMarkerTest.Fixture(directory,GraveDisplayType.DISPLAY_ENTITY,false);
                var builders=mockConstruction(ItemBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),(builder,context)->when(builder.toItemStack()).thenReturn(mock(ItemStack.class)))) {
            if(error)doThrow(new AssertionError("synthetic native initialization error")).when(f.display).setItemStack(any());
            else doThrow(new IllegalStateException("synthetic native initialization failure")).when(f.display).setItemStack(any());
            var result=f.h.plugin.recreateBrokenGrave(f.h.grave,null,true);SqlRecreationOwnerTest.runRegion(f.h);
            assertEquals(Material.AIR,f.material.get());assertTrue(f.manager.isRecreationPending(f.h.identity()));assertFalse(result.isDone());
            verify(f.display,never()).remove();f.h.drain();assertFalse(ClaimHarness.await(result));verify(f.display).remove();
            assertFalse(f.manager.isRecreationPending(f.h.identity()));verify(f.backend,never()).saveRecreatedGrave(any());
        }
    }

}
