package com.bencodez.gravestonesplus.tests.graves;

import com.bencodez.gravestonesplus.graves.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.gravestonesplus.listeners.PlayerDeathListener;
import com.bencodez.gravestonesplus.storage.*;
import com.bencodez.gravestonesplus.tests.storage.SqlJournalHarness;
import com.bencodez.advancedcore.api.item.ItemBuilder;

/** Actual death entry, owned timer, journal and manager; native operations are fault-controlled mocks. */
class SqlDeathCallerTest {
    @TempDir Path directory;
    static <T> T await(CompletableFuture<T> f) throws Exception { return f.get(5, TimeUnit.SECONDS); }
    static void field(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    final class Fixture implements AutoCloseable {
        final ClaimHarness h = new ClaimHarness();
        final FeatureIO io = new FeatureIO(128, directory);
        final GraveStorageBackend backend = mock(GraveStorageBackend.class);
        final GraveStorageManager manager;
        final SqlJournalHarness journal;
        final Block marker = mock(Block.class);
        final Queue<Runnable> timers = new ConcurrentLinkedQueue<>();
        final PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        final ArrayList<org.bukkit.inventory.ItemStack> drops = new ArrayList<>();
        final org.mockito.MockedStatic<InventorySerializer> encoding = mockStatic(InventorySerializer.class);
        Fixture() throws Exception {
            assertTrue(await(io.ready()));
            encoding.when(() -> InventorySerializer.encode(any())).thenReturn("synthetic-reward");
            when(h.plugin.getFeatureIO()).thenReturn(io);
            manager = new GraveStorageManager(h.plugin); field(manager,"backend",backend); field(manager,"type",DataStorageType.MYSQL);
            when(h.plugin.getStorageManager()).thenReturn(manager);
            when(h.plugin.getKey()).thenReturn(new NamespacedKey("gravestonesplus","gravestoneplusholograms"));
            ClaimHarness.pluginField(h.plugin,"brokenGraves",Collections.synchronizedList(new ArrayList<Grave>()));
            when(h.plugin.removeGrave(any(),any(SqlRecoveryJournal.Reservation.class))).thenCallRealMethod();
            ClaimHarness.pluginField(h.plugin,"featureIO",io); ClaimHarness.pluginField(h.plugin,"storageManager",manager);
            doCallRealMethod().when(h.plugin).onUnLoad();
            when(h.plugin.registerRecreationAttempt(any())).thenCallRealMethod();
            doCallRealMethod().when(h.plugin).unregisterRecreationAttempt(any());
            doCallRealMethod().when(h.plugin).retireRecreationAttempts();
            doCallRealMethod().when(h.plugin).publishSqlCreatedGrave(any());
            journal = new SqlJournalHarness(io,backend,manager,directory.resolve("sql-recovery"));
            when(h.folia.isFolia()).thenReturn(true);
            when(h.config.getDisabledWorlds()).thenReturn(new ArrayList<>());
            when(h.config.getGraveLimit()).thenReturn(10); when(h.config.getKeepItemsWithLore()).thenReturn("");
            when(h.config.getFormatDeath()).thenReturn(""); when(h.config.getGraveTimeLimit()).thenReturn("0");
            when(h.config.getGraveDisplayTypeEnum()).thenReturn(GraveDisplayType.CHEST);
            when(h.world.getMinHeight()).thenReturn(-64); when(h.world.getMaxHeight()).thenReturn(320);
            when(marker.getLocation()).thenReturn(new Location(h.world,1,64,1));
            when(marker.isEmpty()).thenReturn(true); when(marker.getType()).thenReturn(Material.AIR);
            when(h.world.getBlockAt(anyInt(),anyInt(),anyInt())).thenReturn(marker);
            when(h.world.getBlockAt(any(Location.class))).thenReturn(marker);
            when(h.owner.getName()).thenReturn("Owner"); when(h.owner.getDisplayName()).thenReturn("Owner");
            when(h.owner.hasPermission("GraveStonesPlus.AllowGrave")).thenReturn(true);
            when(h.ownerInventory.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[]{h.stored});
            when(h.ownerInventory.getArmorContents()).thenReturn(new org.bukkit.inventory.ItemStack[4]);
            when(h.ownerInventory.getItem(4)).thenReturn(h.stored);
            doAnswer(call -> {
                Runnable work=call.getArgument(1); var task=mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class);
                doAnswer(cancel -> { timers.remove(work); return null; }).when(task).cancel(); timers.add(work); return task;
            }).when(h.platform).runAtLocationTimer(any(Location.class),any(Runnable.class),eq(2L),eq(1L),eq(TimeUnit.SECONDS));
            drops.add(h.stored); when(death.getDrops()).thenReturn(drops); when(death.getDroppedExp()).thenReturn(17); when(death.getEntity()).thenReturn(h.owner);
        }
        void capture() { new PlayerDeathListener(h.plugin).onPlayerDeath(death); }
        void timer() { var work=timers.poll(); assertNotNull(work); work.run(); }
        void retire() { when(h.plugin.isEnabled()).thenReturn(false); h.plugin.retireRecreationAttempts(); manager.close(); }
        void unchanged() { assertEquals(List.of(h.stored),drops); verify(death,never()).setDroppedExp(anyInt()); assertFalse(h.grave.getGravesConfig().isDestroyed()); verify(marker,never()).setType(any()); verify(backend,never()).upsertGrave(any(),anyBoolean()); }
        @Override public void close() throws Exception { retire(); io.close(); encoding.close(); h.close(); }
    }
    @Test void realWorkerSaturationRejectsBeforeRewardsOrOldGraveEffects() throws Exception {
        try(var f=new Fixture()) {
            var slots=new ArrayList<FeatureIO.Admission>(); for(int n=0;n<129;n++) slots.add(f.io.admit()); assertNotNull(slots.get(128));
            try { f.capture(); f.unchanged(); assertTrue(f.timers.isEmpty()); }
            finally { slots.forEach(FeatureIO.Admission::close); }
        }
    }
    @Test void encodedByteRejectionLeavesRewardsAndOldGraveUntouched() throws Exception {
        try(var f=new Fixture()) {
            f.encoding.when(() -> InventorySerializer.encode(any())).thenReturn("x".repeat(2_100_000));
            f.capture(); f.unchanged(); assertTrue(f.timers.isEmpty());
        }
    }
    @Test void schedulerRejectionCancelsUnusedObligationAndLimitPreparation() throws Exception {
        try(var f=new Fixture()) {
            when(f.h.config.getGraveLimit()).thenReturn(1); when(f.h.plugin.numberOfGraves(any())).thenReturn(1);
            when(f.h.plugin.getOldestGrave(any())).thenReturn(f.h.grave); when(f.h.config.isDropItemsOnGraveRemoval()).thenReturn(true);
            when(f.h.platform.runAtLocationTimer(any(Location.class),any(Runnable.class),eq(2L),eq(1L),eq(TimeUnit.SECONDS))).thenReturn(null);
            f.capture(); f.unchanged(); assertNull(f.h.ledger.status(f.h.identity()));
            assertEquals(0,f.journal.records());
        }
    }
    @Test void acceptedDeferredDeathRetiredBeforeTimerPersistsOnceWithoutNativeActor() throws Exception {
        try(var f=new Fixture()) {
            f.capture(); assertTrue(f.drops.isEmpty()); verify(f.death).setDroppedExp(0);
            f.retire(); f.io.close();
            verify(f.backend,times(1)).upsertGrave(argThat(g -> g.getExp()==17 && g.getDisplayUUID()==null),eq(false));
            verify(f.marker,never()).setType(any()); assertTrue(f.io.isGenerationReleased());
            // A stale callback from the old generation cannot mutate a reenabled instance.
            when(f.h.plugin.isEnabled()).thenReturn(true); f.timer(); verify(f.marker,never()).setType(any());
        }
    }

    @Test void journalEntryCapRejectsActualDeathWithoutAnyEffects() throws Exception {
        try(var f=new Fixture()) {
            var slots=new ArrayList<SqlRecoveryJournal.Reservation>();
            for(int n=0;n<256;n++){var slot=f.manager.reserveSqlTransition(SqlRecoveryJournal.Kind.FLAG,f.h.grave.getGravesConfig(),false);assertNotNull(slot);slots.add(slot);
                if(n>0){slot.commit();await(f.io.barrier());}}
            try {f.capture();f.unchanged();assertTrue(f.timers.isEmpty());}
            finally {slots.forEach(SqlRecoveryJournal.Reservation::close);}
        }
    }
    @Test void reentrantUnloadDuringSchedulingRejectsBeforeCaptureAndOldLimitEffects() throws Exception {
        try(var f=new Fixture()) {
            doAnswer(call->{when(f.h.plugin.isEnabled()).thenReturn(false);f.h.plugin.onUnLoad();
                return mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class);
            }).when(f.h.platform).runAtLocationTimer(any(Location.class),any(Runnable.class),eq(2L),eq(1L),eq(TimeUnit.SECONDS));
            f.capture();f.unchanged();assertTrue(f.io.isGenerationReleased());
        }
    }
    @Test void acceptedRamHeldDeathBlocksUnloadAndSuccessorUntilExactPublicationRepair() throws Exception {
        try(var f=new Fixture()) {
            f.capture();Files.delete(f.journal.directory);Files.writeString(f.journal.directory,"synthetic obstruction");
            f.retire();await(f.io.barrier());assertFalse(f.journal.journal.isReady());
            var close=CompletableFuture.runAsync(f.io::close);
            try {
                assertThrows(TimeoutException.class,()->close.get(100,TimeUnit.MILLISECONDS));
                try(var contender=new FeatureIO(2,directory)){assertFalse(await(contender.ready()));}
                verify(f.backend,never()).upsertGrave(any(),anyBoolean());
            } finally {Files.delete(f.journal.directory);Files.createDirectory(f.journal.directory);await(close);}
            assertTrue(f.io.isGenerationReleased());assertEquals(1,f.journal.records());
            verify(f.backend,never()).upsertGrave(any(),anyBoolean());
            try(var next=new FeatureIO(8,directory)) {
                assertTrue(await(next.ready()));var backend=mock(GraveStorageBackend.class);
                var replay=new SqlRecoveryJournal(next,backend,f.journal.directory,ignored->{});assertTrue(await(replay.loaded()));await(next.barrier());
                verify(backend,times(1)).upsertGrave(argThat(g->g.getExp()==17&&g.getDisplayUUID()==null),eq(false));replay.close();
            }
        }
    }


    @ParameterizedTest @ValueSource(ints={0,1,2,3})
    void displayAdoptionUsesEntityOwnerAfterSqlAndRetirementRevokesOnlyItsMetadata(int mode) throws Exception {
        try(var f=new Fixture();var holograms=mockConstruction(com.bencodez.advancedcore.api.hologram.Hologram.class);var builders=mockConstruction(ItemBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),
                (builder,context)->when(builder.toItemStack()).thenReturn(f.h.cloned))) {
            when(f.h.config.getGraveDisplayTypeEnum()).thenReturn(GraveDisplayType.DISPLAY_ENTITY);
            when(f.h.plugin.getGraveDisplayEntityHandler()).thenReturn(mock(GraveDisplayEntityHandle.class));
            var packs=mock(org.bukkit.packs.DataPackManager.class);f.h.bukkit.when(Bukkit::getDataPackManager).thenReturn(packs);
            when(packs.isEnabledByFeature(org.bukkit.entity.EntityType.ITEM_DISPLAY,f.h.world)).thenReturn(true);
            var display=mock(ItemDisplay.class);UUID actual=UUID.randomUUID();when(display.getUniqueId()).thenReturn(actual);
            when(display.isValid()).thenReturn(true);when(display.getWorld()).thenReturn(f.h.world);
            when(display.getLocation()).thenReturn(new Location(f.h.world,1.5,64.5,1.5));
            var data=mock(org.bukkit.persistence.PersistentDataContainer.class);
            var identity=new java.util.concurrent.atomic.AtomicReference<String>();
            when(display.getPersistentDataContainer()).thenReturn(data);
            doAnswer(call->{identity.set(call.getArgument(2));return null;}).when(data).set(any(NamespacedKey.class),eq(org.bukkit.persistence.PersistentDataType.STRING),anyString());
            when(data.get(any(NamespacedKey.class),eq(org.bukkit.persistence.PersistentDataType.STRING))).thenAnswer(call->identity.get());
            var claim=new java.util.concurrent.atomic.AtomicReference<org.bukkit.metadata.MetadataValue>();
            when(display.getMetadata("Grave")).thenAnswer(call->claim.get()==null?List.of():List.of(claim.get()));
            doAnswer(call->{claim.set(null);return null;}).when(display).removeMetadata(eq("Grave"),eq(f.h.plugin));
            doAnswer(call->{claim.set(call.getArgument(1));if(mode==2){when(f.h.plugin.isEnabled()).thenReturn(false);f.h.plugin.onUnLoad();assertFalse(f.io.isGenerationReleased());}return null;})
                    .when(display).setMetadata(eq("Grave"),any());
            doAnswer(call->{Consumer<ItemDisplay> callback=call.getArgument(2);callback.accept(display);return display;})
                    .when(f.h.world).spawn(any(Location.class),eq(ItemDisplay.class),any(Consumer.class));
            if(mode==1)doAnswer(call->{((Runnable)call.getArgument(2)).run();return CompletableFuture.completedFuture(com.bencodez.simpleapi.folialib.enums.EntityTaskResult.ENTITY_RETIRED);})
                    .when(f.h.platform).runAtEntityWithFallback(eq(display),any(),any(Runnable.class));
            var chunk=mock(Chunk.class);when(f.marker.getChunk()).thenReturn(chunk);when(chunk.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
            var release=new CountDownLatch(1);var entered=new CountDownLatch(1);
            f.io.submit(()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}return null;});assertTrue(entered.await(5,TimeUnit.SECONDS));
            try {f.capture();f.timer();assertNotNull(identity.get());verify(display,never()).setMetadata(anyString(),any());}
            finally {release.countDown();}
            await(f.io.barrier());
            if(mode!=1){for(int n=0;n<100&&f.h.entityWork.isEmpty();n++)Thread.sleep(5);var adopt=f.h.entityWork.poll();assertNotNull(adopt);adopt.run();}
            if(mode==0){var publish=f.h.regionWork.poll();assertNotNull(publish);publish.run();assertEquals(2,f.h.active.size());assertNotNull(claim.get());}
            if(mode==1){assertEquals(1,f.h.active.size());verify(display,never()).setMetadata(anyString(),any());}
            if(mode==3){f.retire();f.io.close();assertFalse(f.io.isGenerationReleased());var revoke=f.h.entityWork.poll();assertNotNull(revoke);revoke.run();}
            if(mode==2||mode==3){for(int n=0;n<100&&!f.io.isGenerationReleased();n++)Thread.sleep(5);assertTrue(f.io.isGenerationReleased());assertNull(claim.get());assertEquals(1,f.h.active.size());}
            verify(data).set(eq(f.h.plugin.getKey()),eq(org.bukkit.persistence.PersistentDataType.INTEGER),eq(1));
            verify(f.backend,times(1)).upsertGrave(argThat(g->actual.equals(g.getDisplayUUID())&&g.getExp()==17),eq(false));
            verify(f.h.owner,never()).giveExp(anyInt());
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void chestAndSkullSetterRetirementCannotUpdateOldNativeState(boolean skull) throws Exception {
        try(var f=new Fixture()) {
            Runnable disable=()->{when(f.h.plugin.isEnabled()).thenReturn(false);f.h.plugin.onUnLoad();assertFalse(f.io.isGenerationReleased());};
            if(skull){when(f.h.config.getGraveDisplayTypeEnum()).thenReturn(GraveDisplayType.PLAYER_HEAD);var state=mock(org.bukkit.block.Skull.class);
                when(f.marker.getState()).thenReturn(state);doAnswer(call->{disable.run();return true;}).when(state).setOwningPlayer(any());
                f.capture();f.timer();verify(state,never()).update();
            }else{var state=mock(org.bukkit.block.Chest.class);when(f.marker.getState()).thenReturn(state);
                doAnswer(call->{disable.run();return null;}).when(state).setCustomName(anyString());f.capture();f.timer();verify(state,never()).update(anyBoolean());}
            for(int n=0;n<100&&!f.io.isGenerationReleased();n++)Thread.sleep(5);assertTrue(f.io.isGenerationReleased());
            verify(f.backend,times(1)).upsertGrave(any(),eq(false));
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void oldLimitNativeFrameSurvivesReentrantUnloadBeforeOrAfterMutation(boolean afterMutation) throws Exception {
        try(var f=new Fixture()) {
            when(f.h.config.getGraveLimit()).thenReturn(1);when(f.h.plugin.numberOfGraves(any())).thenReturn(1);when(f.h.plugin.getOldestGrave(any())).thenReturn(f.h.grave);
            Runnable disable=()->{when(f.h.plugin.isEnabled()).thenReturn(false);f.h.plugin.onUnLoad();assertFalse(f.io.isGenerationReleased());};
            if(afterMutation){var navigation=spy(new GraveNavigation(f.h.plugin));when(f.h.plugin.getGraveNavigation()).thenReturn(navigation);doAnswer(call->{disable.run();return null;}).when(navigation).invalidate(anyString());}
            else doAnswer(call->{if(call.getArgument(0) instanceof com.bencodez.gravestonesplus.events.GraveRemoveEvent)disable.run();return null;}).when(f.h.events).callEvent(any());
            f.capture();assertTrue(f.drops.isEmpty());var remove=f.h.regionWork.poll();assertNotNull(remove);remove.run();
            for(int n=0;n<100&&!f.io.isGenerationReleased();n++)Thread.sleep(5);assertTrue(f.io.isGenerationReleased());
            assertEquals(afterMutation,f.h.grave.getGravesConfig().isDestroyed());
            verify(f.backend,times(1)).upsertGrave(any(),eq(false));verify(f.backend,times(afterMutation?1:0)).removeExistingGrave(any());
            verify(f.h.owner,never()).giveExp(anyInt());
        }
    }

    @Test void delayedAndHeldCreationProtectsExactMarkerWithoutClaimsAndRechecksPhysicsSuccessor() throws Exception {
        try(var f=new Fixture()) {
            var restoration=new GraveRestoration(f.h.plugin,f.manager);field(restoration,"recordsLoaded",true);
            when(f.h.plugin.getGraveRestoration()).thenReturn(restoration);ClaimHarness.pluginField(f.h.plugin,"graveRestoration",restoration);
            when(f.marker.getWorld()).thenReturn(f.h.world);when(f.marker.getX()).thenReturn(1);when(f.marker.getY()).thenReturn(64);when(f.marker.getZ()).thenReturn(1);
            when(f.h.world.isChunkLoaded(anyInt(),anyInt())).thenReturn(true);when(f.marker.getType()).thenReturn(Material.CHEST);
            var chunk=mock(Chunk.class);var data=mock(org.bukkit.persistence.PersistentDataContainer.class);when(f.marker.getChunk()).thenReturn(chunk);when(chunk.getPersistentDataContainer()).thenReturn(data);
            var successor=new java.util.concurrent.atomic.AtomicReference<String>();when(data.get(any(NamespacedKey.class),eq(org.bukkit.persistence.PersistentDataType.STRING))).thenAnswer(call->successor.get());
            var release=new CountDownLatch(1);var entered=new CountDownLatch(1);
            f.io.submit(()->{entered.countDown();try{release.await();}catch(InterruptedException e){throw new AssertionError(e);}return null;});assertTrue(entered.await(5,TimeUnit.SECONDS));
            try {
                f.capture();f.timer();assertTrue(restoration.protectsPendingMarker(f.marker));
                var claims=new com.bencodez.gravestonesplus.listeners.GraveClaimListener(f.h.plugin);
                var broken=new org.bukkit.event.block.BlockBreakEvent(f.marker,f.h.owner);claims.onBlockBreak(broken);assertTrue(broken.isCancelled());
                var interaction=mock(org.bukkit.event.player.PlayerInteractEvent.class);when(interaction.getClickedBlock()).thenReturn(f.marker);claims.onPlayerInteract(interaction);verify(interaction).setCancelled(true);
                var protection=new com.bencodez.gravestonesplus.listeners.GraveBlockProtectionListener(f.h.plugin);
                var blast=mock(org.bukkit.event.entity.EntityExplodeEvent.class);var blocks=new ArrayList<>(List.of(f.marker));when(blast.blockList()).thenReturn(blocks);protection.onEntityExplode(blast);assertTrue(blocks.isEmpty());
                var physics=mock(org.bukkit.event.block.BlockPhysicsEvent.class);when(physics.getBlock()).thenReturn(f.marker);protection.onPhysics(physics);verify(physics).setCancelled(true);
                var deferred=f.h.regionWork.poll();assertNotNull(deferred);clearInvocations(f.marker);
                successor.set("synthetic-successor");when(f.marker.getType()).thenReturn(Material.AIR);deferred.run();verify(f.marker,never()).setType(any(),anyBoolean());
                when(f.marker.getType()).thenReturn(Material.CHEST);assertFalse(restoration.protectsPendingMarker(f.marker));
                successor.set(null);when(f.marker.getType()).thenReturn(Material.STONE);assertFalse(restoration.protectsPendingMarker(f.marker));
                when(f.marker.getType()).thenReturn(Material.CHEST);assertTrue(restoration.protectsPendingMarker(f.marker));
                verify(f.h.owner,never()).giveExp(anyInt());verify(f.h.plugin,never()).publishSqlCreatedGrave(any());
                doThrow(new IllegalStateException("synthetic JDBC outage")).when(f.backend).upsertGrave(any(),anyBoolean());
            } finally {release.countDown();}
            await(f.io.barrier());assertTrue(restoration.protectsPendingMarker(f.marker),"HELD SQL does not release accepted physical protection");
            verify(f.h.plugin,never()).publishSqlCreatedGrave(any());
        }
    }
    @Test void wrapperRetirementBeforeNativeEntrySettlesAcceptedObligationWithoutWorldEffects() throws Exception {
        try(var f=new Fixture()) {
            var restoration=mock(GraveRestoration.class);when(f.h.plugin.getGraveRestoration()).thenReturn(restoration);
            when(restoration.reserveCreation(any())).thenReturn(new GraveRestoration.CreationAdmission(0,null));
            when(restoration.creationGrave(any())).thenThrow(new RejectedExecutionException("retired wrapper"));
            f.capture();assertTrue(f.drops.isEmpty());assertThrows(RejectedExecutionException.class,f::timer);
            await(f.io.barrier());verify(f.backend,times(1)).upsertGrave(any(),eq(false));verify(f.marker,never()).setType(any());
        }
    }
    @Test void preAdmittedOldRemovalWaitsBehindTheSameCreationAndNeverDuplicatesItsUpsert() throws Exception {
        try(var f=new Fixture()) {
            when(f.h.config.getGraveLimit()).thenReturn(1);when(f.h.plugin.numberOfGraves(any())).thenReturn(1);
            when(f.h.plugin.getOldestGrave(any())).thenReturn(f.h.grave);
            var order=new CopyOnWriteArrayList<String>();
            doAnswer(call->{order.add("creation");return null;}).when(f.backend).upsertGrave(any(),anyBoolean());
            doAnswer(call->{order.add("removal");return null;}).when(f.backend).removeExistingGrave(any());
            f.capture();assertTrue(f.drops.isEmpty());assertFalse(f.h.grave.getGravesConfig().isDestroyed());
            var removal=f.h.regionWork.poll();assertNotNull(removal);removal.run();
            assertTrue(f.h.grave.getGravesConfig().isDestroyed());await(f.io.barrier());assertTrue(order.isEmpty());
            f.timer();await(f.io.barrier());assertEquals(List.of("creation","removal"),order);
            verify(f.backend,times(1)).upsertGrave(any(),eq(false));verify(f.h.owner,never()).giveExp(anyInt());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void publicationAndMetadataRetirementNeverContinueIntoNewGeneration(boolean metadata) throws Exception {
        try(var f=new Fixture()) {
            Runnable disable=()->{when(f.h.plugin.isEnabled()).thenReturn(false);f.h.plugin.onUnLoad();assertFalse(f.io.isGenerationReleased());};
            if(metadata)doAnswer(call->{disable.run();return null;}).when(f.marker).removeMetadata(eq("Grave"),eq(f.h.plugin));
            else doAnswer(call->{call.callRealMethod();disable.run();return null;}).when(f.h.plugin).publishSqlCreatedGrave(any());
            f.capture();f.timer();await(f.io.barrier());
            for(int n=0;n<100&&f.h.regionWork.isEmpty();n++)Thread.sleep(5);
            var publish=f.h.regionWork.poll();assertNotNull(publish);publish.run();
            for(int n=0;n<100&&!f.io.isGenerationReleased();n++)Thread.sleep(5);
            assertTrue(f.io.isGenerationReleased());verify(f.marker,never()).setMetadata(anyString(),any());
            verify(f.backend,times(1)).upsertGrave(any(),eq(false));
        }
    }
    @Test void unknownBackendCloseNeverReleasesLeaseOrPretendsUnloadSucceeded() throws Exception {
        try(var f=new Fixture()) {
            doThrow(new IllegalStateException("synthetic unknown backend ownership")).when(f.backend).close();
            f.retire();var close=CompletableFuture.runAsync(f.io::close);
            try {
                assertThrows(TimeoutException.class,()->close.get(100,TimeUnit.MILLISECONDS));
                assertFalse(f.io.isGenerationReleased());
                try(var contender=new FeatureIO(2,directory)){assertFalse(await(contender.ready()));}
                verify(f.backend,times(1)).close();
            } finally {
                // Test fixture disposal only: there is deliberately no production unknown-close repair API.
                // The fixture confirms its simulated resource is now closed before releasing its ownership token.
                doNothing().when(f.backend).close(); f.backend.close();
                var token=GraveStorageManager.class.getDeclaredField("backendRetirement");token.setAccessible(true);
                ((FeatureIO.Settlement)token.get(f.manager)).close();await(close);
            }
        }
    }
    @Test void laterRemovalCannotOvertakeAcceptedCreation() throws Exception {
        try(var f=new Fixture()) {
            var order=new CopyOnWriteArrayList<String>();
            doAnswer(call->{order.add("creation");return null;}).when(f.backend).upsertGrave(any(),anyBoolean());
            doAnswer(call->{order.add("removal");return null;}).when(f.backend).setBroken(any(),eq(true));
            f.capture(); var remove=f.manager.reserveSqlTransition(SqlRecoveryJournal.Kind.FLAG,f.h.grave.getGravesConfig(),true); assertNotNull(remove); remove.commit();
            await(f.io.barrier()); assertTrue(order.isEmpty()); f.retire(); f.io.close(); assertEquals(List.of("creation","removal"),order);
        }
    }
    @ParameterizedTest @ValueSource(ints={0,1,2})
    void displayCheckpointUsesActualUuidBeforeReentrantDisableAndNeverRunsLegacyChunkPrefix(int checkpoint) throws Exception {
        try(var f=new Fixture(); var builders=mockConstruction(ItemBuilder.class,withSettings().defaultAnswer(RETURNS_SELF),
                (builder,context)->when(builder.toItemStack()).thenReturn(f.h.cloned))) {
            when(f.h.config.getGraveDisplayTypeEnum()).thenReturn(GraveDisplayType.DISPLAY_ENTITY);
            when(f.h.plugin.getGraveDisplayEntityHandler()).thenReturn(mock(GraveDisplayEntityHandle.class));
            var packs=mock(org.bukkit.packs.DataPackManager.class);
            f.h.bukkit.when(Bukkit::getDataPackManager).thenReturn(packs);
            when(packs.isEnabledByFeature(org.bukkit.entity.EntityType.ITEM_DISPLAY,f.h.world)).thenReturn(true);
            UUID actual=UUID.randomUUID(); var display=mock(ItemDisplay.class); when(display.getUniqueId()).thenReturn(actual);
            when(display.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
            Runnable disable = () -> {
                when(f.h.plugin.isEnabled()).thenReturn(false); f.h.plugin.onUnLoad();
                assertFalse(f.io.isGenerationReleased(),"Actual native frame still owns generation");
                try(var contender=new FeatureIO(2,directory)){assertFalse(await(contender.ready()));}
                catch(Exception failure){throw new AssertionError(failure);}
            };
            if(checkpoint==0) doAnswer(call->{disable.run();return null;}).when(display).setItemStack(any());
            if(checkpoint==2) doAnswer(call->{disable.run();return null;}).when(f.marker).setType(Material.BARRIER);
            doAnswer(call->{ Consumer<ItemDisplay> callback=call.getArgument(2); callback.accept(display);
                if(checkpoint==1) disable.run();
                return display;
            }).when(f.h.world).spawn(any(Location.class),eq(ItemDisplay.class),any(Consumer.class));
            f.capture(); assertTrue(f.drops.isEmpty()); f.timer();
            verify(f.backend,times(1)).upsertGrave(argThat(g->actual.equals(g.getDisplayUUID())&&g.getExp()==17),eq(false));
            for(int n=0;n<100&&!f.io.isGenerationReleased();n++) Thread.sleep(10);
            assertTrue(f.io.isGenerationReleased());
            verify(display.getPersistentDataContainer()).set(eq(GraveMarkerCleanup.identityKey(f.h.plugin)),eq(org.bukkit.persistence.PersistentDataType.STRING),anyString());
            verify(display.getPersistentDataContainer()).set(eq(f.h.plugin.getKey()),eq(org.bukkit.persistence.PersistentDataType.INTEGER),eq(1));
            if(checkpoint==0)verify(display,never()).setVisibleByDefault(anyBoolean());
            verify(f.h.world,never()).loadChunk(anyInt(),anyInt());
            verify(f.marker,times(checkpoint==2?1:0)).setType(any());
        }
    }
}
