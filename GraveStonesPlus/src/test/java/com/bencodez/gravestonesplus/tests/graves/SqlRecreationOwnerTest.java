package com.bencodez.gravestonesplus.tests.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.gravestonesplus.graves.*;
import com.bencodez.gravestonesplus.storage.*;
import com.bencodez.gravestonesplus.tests.storage.SqlJournalHarness;

class SqlRecreationOwnerTest {
    @TempDir Path directory;
    static void field(Object target,String name,Object value)throws Exception {
        var field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
    static List<Grave> install(ClaimHarness h,GraveStorageManager manager)throws Exception {
        when(h.plugin.getFeatureIO()).thenReturn(h.io);when(h.plugin.getStorageManager()).thenReturn(manager);
        field(h.plugin,"storageManager",manager);
        h.active.clear();h.grave.getGravesConfig().setDestroyed(true);h.grave.getGravesConfig().setDestroyedTime(2000);
        var broken=Collections.synchronizedList(new ArrayList<>(List.of(h.grave)));field(h.plugin,"brokenGraves",broken);
        doCallRealMethod().when(h.plugin).recreateBrokenGrave(h.grave,null,false);
        doCallRealMethod().when(h.plugin).publishSqlRecreatedGrave(h.grave);
        return broken;
    }
    static GraveStorageManager manager(ClaimHarness h,GraveStorageBackend backend)throws Exception {
        when(h.plugin.getFeatureIO()).thenReturn(h.io);
        var manager=new GraveStorageManager(h.plugin);field(manager,"backend",backend);field(manager,"type",DataStorageType.MYSQL);return manager;
    }
    static void runRegion(ClaimHarness h)throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(h.regionWork.isEmpty()&&System.nanoTime()<until){h.drain();Thread.sleep(1);}
        assertFalse(h.regionWork.isEmpty());h.regionWork.remove().run();
    }
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void noMarkerRecreationStaysHistoricalUntilSqlAndOwnerPublication(int observerMode)throws Exception {
        try(var h=new ClaimHarness()) {
            var backend=mock(GraveStorageBackend.class);var manager=manager(h,backend);var broken=install(h,manager);
            var config=h.grave.getGravesConfig();config.setGraveDisplayType("legacy-".repeat(50));
            UUID display=UUID.randomUUID(),interact=UUID.randomUUID();config.setDisplayUUID(display);config.setInteractUUID(interact);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            doAnswer(call->{assertTrue(h.io.isOwnerThread());entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;}).when(backend).saveRecreatedGrave(any());
            try(var journal=new SqlJournalHarness(h.io,backend,manager,directory)) {
                var observer=h.plugin.recreateBrokenGrave(h.grave,null,false);runRegion(h);
                try {
                    assertTrue(entered.await(3,TimeUnit.SECONDS));assertTrue(config.isDestroyed());assertTrue(h.active.isEmpty());assertEquals(List.of(h.grave),broken);
                    assertTrue(manager.isRecreationPending(h.identity()));assertFalse(observer.isDone());
                    if(observerMode==1)assertTrue(observer.cancel(true));
                    if(observerMode==2)h.retireAcknowledgments();
                }finally{release.countDown();}
                h.drain();assertTrue(config.isDestroyed());assertTrue(manager.isRecreationPending(h.identity()));
                runRegion(h);h.drain();
                if(observerMode==0)assertTrue(ClaimHarness.await(observer));
                if(observerMode==2)assertFalse(ClaimHarness.await(observer));
                if(observerMode==2) {
                    assertTrue(config.isDestroyed());assertTrue(h.active.isEmpty());assertEquals(List.of(h.grave),broken);
                    assertTrue(manager.isRecreationPending(h.identity()));
                }else {
                    assertFalse(config.isDestroyed());assertEquals(0,config.getDestroyedTime());assertEquals(List.of(h.grave),h.active);assertTrue(broken.isEmpty());
                    assertFalse(manager.isRecreationPending(h.identity()));
                }
                assertEquals(display,config.getDisplayUUID());assertEquals(interact,config.getInteractUUID());assertEquals("legacy-".repeat(50),config.getGraveDisplayType());
                verify(backend,times(1)).saveRecreatedGrave(any());verify(backend,never()).upsertGrave(any(),anyBoolean());verify(h.owner,never()).giveExp(anyInt());
                verify(h.world,never()).getBlockAt(anyInt(),anyInt(),anyInt());assertEquals(0,journal.records());
            }
        }
    }
    @Test void heldSqlRecreationRetainsHistoricalContentsAndOwnerFence()throws Exception {
        try(var h=new ClaimHarness()) {
            var backend=mock(GraveStorageBackend.class);doThrow(new IllegalStateException("synthetic SQL outage")).when(backend).saveRecreatedGrave(any());
            var manager=manager(h,backend);var broken=install(h,manager);
            try(var journal=new SqlJournalHarness(h.io,backend,manager,directory)) {
                var result=h.plugin.recreateBrokenGrave(h.grave,null,false);runRegion(h);h.drain();
                assertFalse(ClaimHarness.await(result));assertTrue(h.grave.getGravesConfig().isDestroyed());assertTrue(h.active.isEmpty());assertEquals(List.of(h.grave),broken);
                assertTrue(manager.isRecreationPending(h.identity()));assertEquals(1,journal.records());assertEquals(17,h.grave.getGravesConfig().getExp());assertFalse(h.grave.getGravesConfig().getItems().isEmpty());
                assertNull(manager.reserveRecreation(h.grave.getGravesConfig(),false));verify(h.owner,never()).giveExp(anyInt());
            }
        }
    }
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void rejectionConfirmedMissAndPreActorRetirementKeepHistoricalState(int mode)throws Exception {
        try(var h=new ClaimHarness()) {
            var backend=mock(GraveStorageBackend.class);var manager=manager(h,backend);var broken=install(h,manager);
            if(mode==1)doThrow(new SqlConditionNotMet()).when(backend).saveRecreatedGrave(any());
            try(var journal=new SqlJournalHarness(h.io,backend,manager,directory)) {
                if(mode==0)manager.beginRestoration();
                var result=h.plugin.recreateBrokenGrave(h.grave,null,false);h.drain();
                if(mode==2)h.retireAcknowledgments();runRegion(h);h.drain();
                assertFalse(ClaimHarness.await(result));assertTrue(h.grave.getGravesConfig().isDestroyed());assertTrue(h.active.isEmpty());assertEquals(List.of(h.grave),broken);
                assertFalse(manager.isRecreationPending(h.identity()));assertEquals(0,journal.records());
                verify(backend,times(mode==1?1:0)).saveRecreatedGrave(any());verify(h.world,never()).getBlockAt(anyInt(),anyInt(),anyInt());verify(h.owner,never()).giveExp(anyInt());
            }
        }
    }
    @Test void sqlAckFollowedByOwnerSchedulerRejectionRemainsHeldForReconciliation()throws Exception {
        try(var h=new ClaimHarness()) {
            var backend=mock(GraveStorageBackend.class);var manager=manager(h,backend);var broken=install(h,manager);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            doAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;}).when(backend).saveRecreatedGrave(any());
            try(var journal=new SqlJournalHarness(h.io,backend,manager,directory)) {
                var result=h.plugin.recreateBrokenGrave(h.grave,null,false);runRegion(h);
                try {
                    assertTrue(entered.await(3,TimeUnit.SECONDS));
                    var platform=h.plugin.getBukkitScheduler().getFoliaLib().getImpl();
                    doThrow(new java.util.concurrent.RejectedExecutionException("synthetic owner scheduler retirement"))
                        .when(platform).runAtLocation(any(org.bukkit.Location.class),any());
                }finally{release.countDown();}
                h.drain();assertFalse(ClaimHarness.await(result));assertTrue(h.grave.getGravesConfig().isDestroyed());assertTrue(h.active.isEmpty());assertEquals(List.of(h.grave),broken);
                assertTrue(manager.isRecreationPending(h.identity()));assertEquals(0,journal.records());verify(backend,times(1)).saveRecreatedGrave(any());
            }
        }
    }

    @Test void alreadyScheduledBlockAndEntityCleanupRecheckThePendingIdentityFence()throws Exception {
        try(var h=new ClaimHarness()) {
            var backend=mock(GraveStorageBackend.class);var manager=manager(h,backend);install(h,manager);
            var disk=new DurableFeatureState.Disk(){public Map<String,String> load(){return Map.of(GraveIdentity.of(h.grave.getGravesConfig()),"CHEST");}public void save(Map<String,String> values){}};
            var cleanup=new GraveMarkerCleanup(h.plugin,new DurableFeatureState(h.io,disk,128));assertTrue(ClaimHarness.await(cleanup.loaded()));
            try(var journal=new SqlJournalHarness(h.io,backend,manager,directory);var admission=manager.reserveRecreation(h.grave.getGravesConfig(),false)) {
                assertNotNull(admission);
                var parse=GraveMarkerCleanup.class.getDeclaredMethod("marker",String.class,String.class);parse.setAccessible(true);var marker=parse.invoke(null,h.identity(),"CHEST");
                var clean=GraveMarkerCleanup.class.getDeclaredMethod("cleanup",marker.getClass(),org.bukkit.Location.class);clean.setAccessible(true);
                assertFalse((Boolean)clean.invoke(cleanup,marker,h.grave.getGravesConfig().getLocation()));
                var entity=mock(org.bukkit.entity.ItemDisplay.class);
                var reconcile=Grave.class.getDeclaredMethod("reconcileSurvivingHologram",org.bukkit.entity.Entity.class,org.bukkit.Location.class,String.class);reconcile.setAccessible(true);
                reconcile.invoke(h.grave,entity,h.grave.getGravesConfig().getLocation(),h.identity());
                verifyNoInteractions(entity);verify(h.world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
            }finally{cleanup.retire();}
        }
    }

    @Test void queuedNativeRecreationCannotMutateWhileNoMarkerSqlIsPending()throws Exception {
        try(var h=new ClaimHarness()) {
            var backend=mock(GraveStorageBackend.class);var manager=manager(h,backend);var broken=install(h,manager);
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            doAnswer(call->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return null;}).when(backend).saveRecreatedGrave(any());
            try(var journal=new SqlJournalHarness(h.io,backend,manager,directory)) {
                var noMarker=h.plugin.recreateBrokenGrave(h.grave,null,false);h.drain();assertEquals(1,h.regionWork.size());
                var nativeAttempt=h.grave.recreateAsync(null,true);h.drain();assertEquals(2,h.regionWork.size());
                h.regionWork.remove().run();
                try {
                    assertTrue(entered.await(3,TimeUnit.SECONDS));
                    h.regionWork.remove().run();
                    assertTrue(h.grave.getGravesConfig().isDestroyed(),"competing native callback must not clear historical state");
                    assertFalse(ClaimHarness.await(nativeAttempt));assertTrue(h.regionWork.isEmpty());
                    assertTrue(h.active.isEmpty());assertEquals(List.of(h.grave),broken);assertTrue(manager.isRecreationPending(h.identity()));
                    verify(h.world,never()).getBlockAt(anyInt(),anyInt(),anyInt());
                }finally{release.countDown();}
                h.drain();runRegion(h);h.drain();assertTrue(ClaimHarness.await(noMarker));
                assertFalse(h.grave.getGravesConfig().isDestroyed());assertEquals(List.of(h.grave),h.active);assertTrue(broken.isEmpty());
                assertFalse(manager.isRecreationPending(h.identity()));verify(backend,times(1)).saveRecreatedGrave(any());
            }
        }
    }

}
