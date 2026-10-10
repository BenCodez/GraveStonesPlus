package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import com.bencodez.advancedcore.AdvancedCoreConfigOptions;
import com.bencodez.advancedcore.api.item.FullInventoryHandler;
import com.bencodez.gravestonesplus.breaking.PlayerBreakManager;
import com.bencodez.gravestonesplus.events.GraveClaimEvent;
import com.bencodez.gravestonesplus.events.GraveRemoveEvent;
import com.bencodez.gravestonesplus.storage.GraveStorageManager;
import com.bencodez.simpleapi.time.ParsedDuration;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecoveryCompletionTest {
    @TempDir Path directory;

    static void overflow(GraveActionsTest.Harness h) {
        ItemStack occupied = mock(ItemStack.class);
        var type = h.stored.getType(); when(occupied.getType()).thenReturn(type);
        when(h.ownerInventory.getItem(4)).thenReturn(occupied);
        when(h.ownerInventory.addItem(h.cloned)).thenReturn(new HashMap<>(Map.of(0, h.cloned)));
    }

    @Test void claimWaitsForEveryOverflowAcknowledgmentBeforePaidArchiveRemovalAndSuccess() throws Exception {
        try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            overflow(h);
            h.grave.getGravesConfig().getItems().put(5, h.stored);
            ItemStack occupied = h.ownerInventory.getItem(4); when(h.ownerInventory.getItem(5)).thenReturn(occupied);
            var handler = mock(FullInventoryHandler.class); when(h.plugin.getFullInventoryHandler()).thenReturn(handler);
            var first = new CompletableFuture<Void>(); var second = new CompletableFuture<Void>();
            when(handler.giveItemAsync(h.owner, h.cloned)).thenReturn(first, second);
            var ledger = new GraveClaimLedger(new DurableFeatureState(h.io, h.claimDisk, 8192), directory.resolve("archive"));
            FeaturePersistenceTest.await(ledger.loaded()); when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);

            h.grave.claim(h.owner); h.drain();
            assertTrue(ledger.status(h.identity()).startsWith("RESERVED:"));
            assertFalse(Files.exists(directory.resolve("archive")));
            assertFalse(h.grave.getGravesConfig().isDestroyed()); verify(h.plugin, never()).removeGrave(any());
            verify(h.owner, never()).sendMessage("Paid"); verify(h.owner).giveExp(17);

            first.complete(null); h.drain();
            assertTrue(ledger.status(h.identity()).startsWith("RESERVED:"));
            verify(h.plugin, never()).removeGrave(any());
            second.complete(null); h.drain();
            assertTrue(ledger.status(h.identity()).startsWith("PAID:"));
            assertTrue(h.grave.getGravesConfig().isDestroyed()); verify(h.plugin).removeGrave(h.grave);
            verify(h.owner).sendMessage("Paid");
            try (var files = Files.list(directory.resolve("archive"))) { assertEquals(1, files.count()); }
            h.grave.claim(h.owner); h.drain(); verify(h.owner, times(1)).giveExp(17);
        }
    }

    @Test void retiredAdvancedCoreOverflowTaskLeavesExactReservationEvidenceAndNeverAdvertisesPaid() throws Exception {
        try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            overflow(h);
            FullInventoryHandler handler = spy(new FullInventoryHandler(h.plugin) {
                @Override protected long getItemDeliveryTimeoutMillis() { return 20L; }
            });
            try {
                when(h.plugin.getFullInventoryHandler()).thenReturn(handler);
                var delivery = new AtomicReference<CompletableFuture<Void>>();
                doAnswer(call -> {
                    var stage = (CompletionStage<Void>) call.callRealMethod();
                    delivery.set(stage.toCompletableFuture()); return stage;
                }).when(handler).giveItemAsync(h.owner, h.cloned);
                // Keep the claim admission; discard the nested AdvancedCore entity task as Folia retirement does.
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                doAnswer(call -> { if (calls.incrementAndGet() != 2) h.entityWork.add(call.getArgument(1)); return null; })
                        .when(h.scheduler).runTask(eq(h.plugin), any(Runnable.class), any(Entity.class));
                h.grave.claim(h.owner); h.drain(); assertNotNull(delivery.get());
                assertThrows(ExecutionException.class, () -> FeaturePersistenceTest.await(delivery.get())); h.drain();

                assertTrue(h.ledger.status(h.identity()).startsWith("RESERVED:"));
                String payload = h.ledger.status(h.identity()).split(":", 3)[2];
                var evidence = new org.bukkit.configuration.file.YamlConfiguration();
                evidence.loadFromString(new String(Base64.getDecoder().decode(payload), java.nio.charset.StandardCharsets.UTF_8));
                assertEquals(h.identity(), evidence.getString("identity")); assertEquals(17, evidence.getInt("experience"));
                assertEquals(Set.of("4"), evidence.getConfigurationSection("items").getKeys(false));
                assertEquals("DIAMOND", evidence.getString("items.4.type")); assertEquals(2, evidence.getInt("items.4.amount"));
                assertFalse(h.grave.getGravesConfig().isDestroyed()); verify(h.plugin, never()).removeGrave(any());
                verify(h.owner, never()).sendMessage("Paid"); assertTrue(handler.getItems().isEmpty());
                h.grave.claim(h.owner); h.drain(); verify(h.owner, times(1)).giveExp(17);
                var restart = new GraveClaimLedger(new DurableFeatureState(h.io, h.claimDisk, 8192));
                FeaturePersistenceTest.await(restart.loaded()); assertTrue(restart.isBlocked(h.identity()));
            } finally { handler.shutdown(); }
        }
    }

    @Test void failedOverflowAcknowledgmentPreservesReservationAfterOtherItemsWereDelivered() throws Exception {
        try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            overflow(h); var handler = mock(FullInventoryHandler.class); when(h.plugin.getFullInventoryHandler()).thenReturn(handler);
            var completion = new CompletableFuture<Void>(); when(handler.giveItemAsync(h.owner, h.cloned)).thenReturn(completion);
            h.grave.claim(h.owner); h.drain(); completion.completeExceptionally(new IllegalStateException("retention failed")); h.drain();
            assertTrue(h.ledger.status(h.identity()).startsWith("RESERVED:"));
            assertFalse(h.grave.getGravesConfig().isDestroyed()); verify(h.plugin, never()).removeGrave(any());
            verify(h.owner, never()).sendMessage("Paid"); verify(h.owner).giveExp(17);
        }
    }

    static PlayerBreakManager manager(GraveActionsTest.Harness h) {
        PlayerBreakManager manager = new PlayerBreakManager(h.plugin); when(h.plugin.getPlayerBreakManager()).thenReturn(manager);
        when(h.config.isBreakOtherGravesEnabled()).thenReturn(true);
        when(h.config.isBreakOtherGravesRequirePermission()).thenReturn(true);
        when(h.config.isBreakOtherGravesRequireBreakTime()).thenReturn(true);
        when(h.helper.hasPermission("GraveStonesPlus.BreakOtherGraves")).thenReturn(true);
        when(h.config.getBreakOtherGravesTimeBeforeBreakable()).thenReturn(duration("0"));
        when(h.config.getBreakOtherGravesTime()).thenReturn(duration("1s"));
        when(h.config.getBreakOtherGravesHitTimeout()).thenReturn(duration("1m"));
        return manager;
    }
    static ParsedDuration duration(String value) { return ParsedDuration.parse(value, TimeUnit.MILLISECONDS); }
    static void elapsed(PlayerBreakManager manager, GraveActionsTest.Harness h) {
        manager.getActiveBreaks().get(h.helper.getUniqueId()).setStartTime(System.currentTimeMillis() - 2000);
    }
    static void tick(PlayerBreakManager manager, GraveActionsTest.Harness h) throws Exception {
        Method method = PlayerBreakManager.class.getDeclaredMethod("tickPlayer", org.bukkit.entity.Player.class);
        method.setAccessible(true); method.invoke(manager, h.helper);
    }

    @Test void authorizedTimedOtherBreakSurvivesTickAndPaysThroughFinalClaimChecks() throws Exception {
        try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            PlayerBreakManager manager = manager(h);
            assertFalse(manager.handleHit(h.helper, h.grave)); elapsed(manager, h); tick(manager, h);
            assertTrue(manager.isBreaking(h.helper, h.grave)); assertFalse(h.grave.legacyClaimAllowed(h.helper));
            assertTrue(manager.handleHit(h.helper, h.grave)); h.drain();
            verify(h.helper).giveExp(17); verify(h.helperInventory).setItem(4, h.cloned);
            assertTrue(h.grave.getGravesConfig().isDestroyed()); assertTrue(h.ledger.status(h.identity()).startsWith("PAID:"));
        }
    }

    @Test void timedBreakTickRejectsDisabledRevokedOrProtectedOtherGraves() throws Exception {
        for (int change : List.of(0, 1, 2)) try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            PlayerBreakManager manager = manager(h); assertFalse(manager.handleHit(h.helper, h.grave)); elapsed(manager, h);
            if (change == 0) when(h.config.isBreakOtherGravesEnabled()).thenReturn(false);
            else if (change == 1) when(h.helper.hasPermission("GraveStonesPlus.BreakOtherGraves")).thenReturn(false);
            else when(h.config.getBreakOtherGravesTimeBeforeBreakable()).thenReturn(duration("1m"));
            tick(manager, h); assertFalse(manager.isBreaking(h.helper, h.grave));
            assertFalse(manager.handleHit(h.helper, h.grave)); h.drain(); verify(h.helper, never()).giveExp(anyInt());
        }
    }

    @Test void completedTimedBreakIsInvalidAfterReloadPermissionLossOrLongerDurationDuringReservation() throws Exception {
        for (int change : List.of(0, 1, 2)) try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            CompletableFuture<Void> reload = null;
            PlayerBreakManager manager = manager(h); manager.handleHit(h.helper, h.grave); elapsed(manager, h);
            assertTrue(manager.handleHit(h.helper, h.grave)); h.entityWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            if (change == 0) {
                GraveActionsTest.field(h.plugin, "playerBreakManager", manager);
                GraveActionsTest.field(h.plugin, "featureIO", h.io);
                GraveActionsTest.field(h.plugin, "configFile", h.config);
                GraveActionsTest.field(h.plugin, "gravesConfig", mock(com.bencodez.gravestonesplus.storage.GraveLocations.class));
                GraveActionsTest.field(h.plugin, "nbtConfigManager", mock(com.bencodez.gravestonesplus.nbt.NBTConfigManager.class));
                reloadPaths(h);
            doCallRealMethod().when(h.plugin).reloadAsync();
                when(h.plugin.getJavascriptEngine()).thenReturn(new HashMap<>());
                doAnswer(call -> { h.entityWork.add(call.getArgument(1)); return null; })
                        .when(h.scheduler).runTask(eq(h.plugin), any(Runnable.class));
                reload = h.plugin.reloadAsync();
                assertFalse(reload.isDone(), "reload must not complete before the owner callback runs");
            }
            else if (change == 1) when(h.helper.hasPermission("GraveStonesPlus.BreakOtherGraves")).thenReturn(false);
            else when(h.config.getBreakOtherGravesTime()).thenReturn(duration("1m"));
            h.drain(); if (reload != null) assertTrue(reload.isDone()); verify(h.helper, never()).giveExp(anyInt()); assertFalse(h.ledger.isBlocked(h.identity()));
            assertFalse(h.grave.getGravesConfig().isDestroyed());
        }
    }

    private void reloadPaths(GraveActionsTest.Harness h) throws Exception {
        Path config = directory.resolve("Config.yml"), graves = directory.resolve("Graves.yml");
        Files.writeString(config, "Root: {}\n"); Files.writeString(graves, "");
        Files.writeString(directory.resolve("nbt_rules.yml"), "Root: {}\n");
        when(h.plugin.getDataFolder()).thenReturn(directory.toFile());
        when(h.config.getdFile()).thenReturn(config.toFile());
        var locations = mock(com.bencodez.gravestonesplus.storage.GraveLocations.class);
        when(locations.getdFile()).thenReturn(graves.toFile());
        GraveActionsTest.field(h.plugin, "gravesConfig", locations);
    }

    @Test void malformedYamlFailsBeforeConfigMutationOrOwnerPublication() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            GraveActionsTest.field(h.plugin, "featureIO", h.io);
            GraveActionsTest.field(h.plugin, "configFile", h.config);
            reloadPaths(h);
            Files.writeString(directory.resolve("Config.yml"), "broken: [\n");
            doCallRealMethod().when(h.plugin).reloadAsync();
            assertThrows(ExecutionException.class, () -> FeaturePersistenceTest.await(h.plugin.reloadAsync()));
            verify(h.config, never()).reloadData();
            verify(h.scheduler, never()).runTask(eq(h.plugin), any(Runnable.class));
        }
    }

    @Test void missingNbtRulesAreRecreatedBeforeValidationAndOwnerPublication() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            GraveActionsTest.field(h.plugin, "featureIO", h.io);
            GraveActionsTest.field(h.plugin, "configFile", h.config);
            GraveActionsTest.field(h.plugin, "nbtConfigManager", mock(com.bencodez.gravestonesplus.nbt.NBTConfigManager.class));
            reloadPaths(h);
            Path rules = directory.resolve("nbt_rules.yml");
            Files.delete(rules);
            doAnswer(call -> { Files.writeString(rules, "ignore_nbt_rules: []\n"); return null; })
                    .when(h.plugin).saveResource("nbt_rules.yml", false);
            when(h.plugin.getJavascriptEngine()).thenReturn(new HashMap<>());
            when(h.plugin.activeGraveSnapshot()).thenReturn(List.of());
            doAnswer(call -> { h.entityWork.add(call.getArgument(1)); return null; })
                    .when(h.scheduler).runTask(eq(h.plugin), any(Runnable.class));
            doCallRealMethod().when(h.plugin).reloadAsync();
            var reload = h.plugin.reloadAsync();
            FeaturePersistenceTest.await(h.io.barrier());
            assertTrue(Files.exists(rules));
            verify(h.plugin).saveResource("nbt_rules.yml", false);
            assertFalse(reload.isDone());
            h.entityWork.remove().run();
            FeaturePersistenceTest.await(reload);
            verify(h.plugin).reloadAdvancedCore(false);
        }
    }

    @Test void reloadOwnerCompletionRejectsConcurrentDisabledAndFailedPublication() throws Exception {
        for (int outcome : List.of(0, 1, 2)) try (var h = new GraveActionsTest.Harness()) {
            GraveActionsTest.field(h.plugin, "featureIO", h.io);
            GraveActionsTest.field(h.plugin, "configFile", h.config);
            GraveActionsTest.field(h.plugin, "gravesConfig", mock(com.bencodez.gravestonesplus.storage.GraveLocations.class));
            GraveActionsTest.field(h.plugin, "nbtConfigManager", mock(com.bencodez.gravestonesplus.nbt.NBTConfigManager.class));
            when(h.plugin.getJavascriptEngine()).thenReturn(new HashMap<>());
            when(h.plugin.activeGraveSnapshot()).thenReturn(List.of());
            doAnswer(call -> { h.entityWork.add(call.getArgument(1)); return null; })
                    .when(h.scheduler).runTask(eq(h.plugin), any(Runnable.class));
            reloadPaths(h);
            doCallRealMethod().when(h.plugin).reloadAsync();
            var reload = h.plugin.reloadAsync();
            FeaturePersistenceTest.await(h.io.barrier());
            assertFalse(reload.isDone());
            var duplicate = h.plugin.reloadAsync();
            assertThrows(ExecutionException.class, () -> FeaturePersistenceTest.await(duplicate));
            if (outcome == 1) when(h.plugin.isEnabled()).thenReturn(false);
            if (outcome == 2) doThrow(new IllegalStateException("owner failed")).when(h.plugin).reloadAdvancedCore(false);
            h.entityWork.remove().run();
            if (outcome == 0) { FeaturePersistenceTest.await(reload); verify(h.plugin).reloadAdvancedCore(false); }
            else assertThrows(ExecutionException.class, () -> FeaturePersistenceTest.await(reload));
            if (outcome == 1) verify(h.plugin, never()).reloadAdvancedCore(false);
        }
    }

    @Test void reloadAsyncPropagatesReadFailureWithoutSchedulingOwnerPublication() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            GraveActionsTest.field(h.plugin, "featureIO", h.io);
            GraveActionsTest.field(h.plugin, "configFile", h.config);
            GraveActionsTest.field(h.plugin, "gravesConfig", mock(com.bencodez.gravestonesplus.storage.GraveLocations.class));
            GraveActionsTest.field(h.plugin, "nbtConfigManager", mock(com.bencodez.gravestonesplus.nbt.NBTConfigManager.class));
            doThrow(new IllegalStateException("read failed")).when(h.config).reloadData();
            reloadPaths(h);
            doCallRealMethod().when(h.plugin).reloadAsync();
            var reload = h.plugin.reloadAsync();
            assertThrows(ExecutionException.class, () -> FeaturePersistenceTest.await(reload));
            verify(h.scheduler, never()).runTask(eq(h.plugin), any(Runnable.class));
        }
    }

    @Test void timedClaimRevalidatesPermissionAndCancellationAfterClaimAndRemovalEvents() throws Exception {
        for (int change : List.of(0, 1, 2)) try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            PlayerBreakManager manager = manager(h); manager.handleHit(h.helper, h.grave); elapsed(manager, h);
            doAnswer(call -> {
                if (call.getArgument(0) instanceof GraveClaimEvent event && change == 0) event.setCancelled(true);
                if (call.getArgument(0) instanceof GraveRemoveEvent event) {
                    if (change == 1) event.setCancelled(true);
                    if (change == 2) when(h.helper.hasPermission("GraveStonesPlus.BreakOtherGraves")).thenReturn(false);
                }
                return null;
            }).when(h.events).callEvent(any());
            assertTrue(manager.handleHit(h.helper, h.grave)); h.drain();
            verify(h.helper, never()).giveExp(anyInt()); assertFalse(h.ledger.isBlocked(h.identity()));
        }
    }

    @Test void archivedRecreationIsDeniedAfterRestartAndAfterCompletionCacheEvictionBeforeAnyMarkerOrListMutation() throws Exception {
        for (boolean restart : List.of(false, true)) try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            Path archive = directory.resolve(restart ? "restart" : "eviction");
            var state = new DurableFeatureState(h.io, h.claimDisk, 8192); var ledger = new GraveClaimLedger(state, archive);
            FeaturePersistenceTest.await(ledger.loaded());
            assertTrue(FeaturePersistenceTest.await(ledger.reserve(h.identity(), h.owner.getUniqueId())));
            assertTrue(FeaturePersistenceTest.await(ledger.paid(h.identity(), h.owner.getUniqueId())));
            if (restart) { ledger = new GraveClaimLedger(new DurableFeatureState(h.io, h.claimDisk, 8192), archive); FeaturePersistenceTest.await(ledger.loaded()); }
            else for (int index = 0; index < 513; index++) {
                assertTrue(FeaturePersistenceTest.await(ledger.reserve("completed-" + index, h.owner.getUniqueId())));
                assertTrue(FeaturePersistenceTest.await(ledger.paid("completed-" + index, h.owner.getUniqueId())));
            }
            assertFalse(ledger.isBlocked(h.identity()), "The cache alone cannot establish absence from archived history");
            when(h.plugin.getGraveClaimLedger()).thenReturn(ledger); GraveActionsTest.field(h.plugin, "graveClaimLedger", ledger);
            var storage = mock(GraveStorageManager.class); GraveActionsTest.field(h.plugin, "storageManager", storage);
            GraveActionsTest.field(h.plugin, "brokenGraves", Collections.synchronizedList(new ArrayList<>(List.of(h.grave))));
            h.active.clear(); h.grave.getGravesConfig().setDestroyed(true);
            doCallRealMethod().when(h.plugin).recreateBrokenGrave(any(), any());
            doCallRealMethod().when(h.plugin).recreateBrokenGrave(any(), any(), anyBoolean());
            when(h.helper.hasPermission("GraveStonesPlus.Admin")).thenReturn(true);

            assertFalse(FeaturePersistenceTest.await(h.plugin.recreateBrokenGrave(h.grave, h.helper)));
            h.grave.createBrokenGrave(); h.drain();
            assertTrue(h.active.isEmpty()); assertTrue(h.grave.getGravesConfig().isDestroyed());
            assertTrue(h.regionWork.isEmpty()); verifyNoInteractions(storage);
            verify(h.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
            verify(h.world, never()).getChunkAt(anyInt(), anyInt());
        }
    }

    @Test void unknownArchiveAndRevokedRecreationPermissionFailBeforeWorldMutation() throws Exception {
        try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            var ledger = new GraveClaimLedger(new DurableFeatureState(h.io, h.claimDisk, 8192), directory.resolve("archive"));
            FeaturePersistenceTest.await(ledger.loaded()); when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            h.grave.getGravesConfig().setDestroyed(true); h.active.clear();
            when(h.helper.hasPermission("GraveStonesPlus.Admin")).thenReturn(true);
            var recreation = h.grave.recreateAsync(h.helper, true);
            FeaturePersistenceTest.await(h.io.barrier()); FeaturePersistenceTest.await(h.io.barrier());
            when(h.helper.hasPermission("GraveStonesPlus.Admin")).thenReturn(false); h.entityWork.remove().run(); h.drain();
            assertFalse(FeaturePersistenceTest.await(recreation)); assertTrue(h.regionWork.isEmpty());
            assertTrue(h.grave.getGravesConfig().isDestroyed());

            assertTrue(FeaturePersistenceTest.await(ledger.reserve(h.identity(), h.owner.getUniqueId())));
            assertTrue(FeaturePersistenceTest.await(ledger.paid(h.identity(), h.owner.getUniqueId())));
            Path tombstone; try (var files = Files.list(directory.resolve("archive"))) { tombstone = files.findFirst().orElseThrow(); }
            Files.writeString(tombstone, "entries: broken");
            var restart = new GraveClaimLedger(new DurableFeatureState(h.io, h.claimDisk, 8192), directory.resolve("archive"));
            FeaturePersistenceTest.await(restart.loaded()); when(h.plugin.getGraveClaimLedger()).thenReturn(restart);
            assertFalse(FeaturePersistenceTest.await(h.grave.recreateAsync(null, true)));
            assertTrue(h.regionWork.isEmpty()); assertTrue(h.grave.getGravesConfig().isDestroyed());
        }
    }

    @Test void reminderCapacityReservesPendingIdentitiesAndCountsPublishedOverlapOnce() throws Exception {
        var disk = new FeaturePersistenceTest.Disk(); Map<String, String> initial = new HashMap<>();
        for (int index = 0; index < 8100; index++) initial.put("existing-" + index + "/200", "sent");
        disk.values = initial;
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, disk, 8192); var store = new GraveReminderStore(state); FeaturePersistenceTest.await(store.loaded());
            disk.entered = new CountDownLatch(1); disk.release = new CountDownLatch(1);
            assertEquals(64, store.deliver(notices(0, 64, 200), 100, fresh -> true).size());
            assertTrue(disk.entered.await(2, TimeUnit.SECONDS));
            try {
                assertTrue(store.deliver(notices(64, 64, 200), 100, fresh -> { fail("chat sent beyond reserved suppression capacity"); return true; }).isEmpty());
                assertEquals(8100, state.snapshot().size());
            } finally { disk.release.countDown(); }
            FeaturePersistenceTest.await(io.barrier()); assertEquals(8164, state.snapshot().size());
            assertEquals(28, store.deliver(notices(64, 28, 200), 100, fresh -> true).size());
            FeaturePersistenceTest.await(io.barrier()); assertEquals(8192, state.snapshot().size()); assertTrue(state.isReady());
            assertTrue(store.deliver(notices(92, 1, 200), 100, fresh -> { fail("full store sent chat"); return true; }).isEmpty());
            assertEquals(1, store.deliver(notices(92, 1, 300), 201, fresh -> true).size());
            FeaturePersistenceTest.await(io.barrier()); assertEquals(1, state.snapshot().size());
            var restart = new GraveReminderStore(new DurableFeatureState(io, disk, 8192)); FeaturePersistenceTest.await(restart.loaded());
            assertTrue(restart.deliver(notices(92, 1, 300), 201, fresh -> { fail("healthy restart repeated acknowledged reminder"); return true; }).isEmpty());
        }
    }
    static List<GraveReminderStore.Notice> notices(int start, int count, long deadline) {
        List<GraveReminderStore.Notice> notices = new ArrayList<>();
        for (int index = start; index < start + count; index++) notices.add(new GraveReminderStore.Notice("new-" + index + "/" + deadline, deadline, "expires soon"));
        return notices;
    }
}
