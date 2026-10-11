package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.config.Config;
import com.bencodez.gravestonesplus.events.*;
import com.bencodez.gravestonesplus.storage.GravesConfig;
import com.bencodez.simpleapi.scheduler.BukkitScheduler;
import com.bencodez.simpleapi.time.ParsedDuration;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class GraveActionsTest {
    static void field(Object target, String name, Object value) throws Exception {
        Field field = GraveStonesPlus.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    static void retryOnOwner(Harness h, GraveRestoration restoration) {
        h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
        try { restoration.retry(); Runnable task; while ((task = h.ownerWork.poll()) != null) task.run(); }
        finally { h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(false); }
    }
    static final class Harness implements AutoCloseable {
        final FeatureIO io = new FeatureIO();
        final FeaturePersistenceTest.Disk accessDisk = new FeaturePersistenceTest.Disk(), claimDisk = new FeaturePersistenceTest.Disk();
        final TrustedGraveAccessStore access = new TrustedGraveAccessStore(new DurableFeatureState(io, accessDisk, 4096), System::currentTimeMillis);
        final GraveClaimLedger ledger = new GraveClaimLedger(new DurableFeatureState(io, claimDisk, 8192));
        final GraveStonesPlus plugin = mock(GraveStonesPlus.class);
        final Config config = mock(Config.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final com.bencodez.simpleapi.folialib.FoliaLib folia = mock(com.bencodez.simpleapi.folialib.FoliaLib.class);
        final com.bencodez.simpleapi.folialib.impl.ServerImplementation platform = mock(com.bencodez.simpleapi.folialib.impl.ServerImplementation.class);
        final World world = mock(World.class);
        final Player owner = mock(Player.class), helper = mock(Player.class);
        final PlayerInventory ownerInventory = mock(PlayerInventory.class), helperInventory = mock(PlayerInventory.class);
        final PluginManager events = mock(PluginManager.class);
        final Queue<Runnable> entityWork = new ConcurrentLinkedQueue<>(), regionWork = new ConcurrentLinkedQueue<>(), ownerWork = new ConcurrentLinkedQueue<>();
        final List<Grave> active = Collections.synchronizedList(new ArrayList<>());
        final GraveNavigation navigation = new GraveNavigation(plugin);
        final MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
        final Grave grave;
        final ItemStack stored = mock(ItemStack.class), cloned = mock(ItemStack.class);
        Harness() throws Exception {
            when(plugin.getName()).thenReturn("GraveStonesPlus"); when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            when(plugin.isEnabled()).thenReturn(true); when(plugin.getConfigFile()).thenReturn(config);
            when(scheduler.getFoliaLib()).thenReturn(folia); when(folia.getImpl()).thenReturn(platform);
            when(plugin.getBukkitScheduler()).thenReturn(scheduler); when(plugin.getTrustedGraveAccessStore()).thenReturn(access);
            when(plugin.getHologramHandler()).thenReturn(new com.bencodez.advancedcore.api.hologram.HologramHandler(plugin));
            when(plugin.getGraveClaimLedger()).thenReturn(ledger); when(plugin.getGraveNavigation()).thenReturn(navigation);
            when(plugin.activeGraveSnapshot()).thenCallRealMethod(); when(plugin.isLiveGrave(any())).thenCallRealMethod();
            when(plugin.canRecover(any(), any())).thenCallRealMethod(); when(plugin.canTrack(any(), any())).thenCallRealMethod();
            field(plugin, "graves", active); field(plugin, "trustedGraveAccessStore", access); field(plugin, "graveClaimLedger", ledger);
            when(world.getUID()).thenReturn(UUID.randomUUID()); when(world.getName()).thenReturn("overworld");
            for (Player player : List.of(owner, helper)) {
                when(player.getUniqueId()).thenReturn(UUID.randomUUID()); when(player.isOnline()).thenReturn(true);
                when(player.getWorld()).thenReturn(world); when(player.getLocation()).thenReturn(new Location(world, 1, 64, 1));
            }
            when(owner.getInventory()).thenReturn(ownerInventory); when(helper.getInventory()).thenReturn(helperInventory);
            when(ownerInventory.getContents()).thenReturn(new ItemStack[41]); when(helperInventory.getContents()).thenReturn(new ItemStack[41]);
            when(plugin.getGraves(any(Player.class))).thenCallRealMethod(); when(plugin.getAccessibleGraves(any())).thenCallRealMethod();
            when(config.getPercentageDrops()).thenReturn(100); when(config.getFormatGraveBroke()).thenReturn("Paid");
            when(config.getFormatGraveBrokeOther()).thenReturn("Recovered %player%"); when(config.getFormatItemsNotInGrave()).thenReturn("Overflow");
            when(config.getBreakOtherGravesTimeBeforeBreakable()).thenReturn(ParsedDuration.parse("1m", java.util.concurrent.TimeUnit.MINUTES));
            Material material = mock(Material.class); when(material.isAir()).thenReturn(false);
            when(stored.getType()).thenReturn(material); when(cloned.getType()).thenReturn(material); when(stored.clone()).thenReturn(cloned); when(cloned.clone()).thenReturn(cloned);
            when(cloned.serialize()).thenReturn(Map.of("type", "DIAMOND", "amount", 2));
            HashMap<Integer, ItemStack> items = new HashMap<>(); items.put(4, stored);
            grave = new Grave(plugin, new GravesConfig(owner.getUniqueId(), "Owner", new Location(world, 1, 64, 1), items, 17,
                    "test", System.currentTimeMillis(), false, 0, null, null, "PLAYER_HEAD")); active.add(grave);
            bukkit.when(Bukkit::getPluginManager).thenReturn(events); bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            doAnswer(call -> { assertTrue(call.getArgument(2) instanceof Player || call.getArgument(2) instanceof ArmorStand); entityWork.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class), any(Entity.class));
            doAnswer(call -> { regionWork.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class), any(Location.class));
            doAnswer(call -> { active.remove(call.getArgument(0)); return null; }).when(plugin).removeGrave(any());
            doAnswer(call -> {
                java.util.function.Consumer<com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask> work = call.getArgument(1);
                var future = new java.util.concurrent.CompletableFuture<com.bencodez.simpleapi.folialib.enums.EntityTaskResult>();
                entityWork.add(() -> { work.accept(null); future.complete(com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS); }); return future;
            }).when(platform).runAtEntityWithFallback(any(Entity.class), any(), any(Runnable.class));
            doAnswer(call -> {
                java.util.function.Consumer<com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask> work = call.getArgument(1);
                var future = new java.util.concurrent.CompletableFuture<Void>();
                regionWork.add(() -> { work.accept(null); future.complete(null); }); return future;
            }).when(platform).runAtLocation(any(Location.class), any());
            doAnswer(call -> { ownerWork.add(call.getArgument(1)); return null; })
                .when(scheduler).runTask(eq(plugin), any(Runnable.class));
            FeaturePersistenceTest.await(access.loaded()); FeaturePersistenceTest.await(ledger.loaded());
        }
        String identity() { return GraveIdentity.of(grave.getGravesConfig()); }
        void drain() throws Exception {
            for (int i = 0; i < 4; i++) {
                Runnable task;
                while ((task = ownerWork.poll()) != null) {
                    bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
                    try { task.run(); }
                    finally { bukkit.when(Bukkit::isPrimaryThread).thenReturn(false); }
                }
                while ((task = entityWork.poll()) != null) task.run();
                FeaturePersistenceTest.await(io.barrier());
            }
        }
        void grant(TrustedGraveAccess.Kind kind) throws Exception { assertTrue(FeaturePersistenceTest.await(access.grantAsync(identity(), helper.getUniqueId(), kind, System.currentTimeMillis() + 60_000))); }
        public void close() { bukkit.close(); io.close(); }
    }
    @Test void trackingNeverRecoversAndRecoveryNeverTracksWithoutSeparateGrant() throws Exception {
        try (Harness h = new Harness()) {
            h.grant(TrustedGraveAccess.Kind.TRACK);
            assertTrue(h.plugin.canTrack(h.grave, h.helper)); assertFalse(h.plugin.canRecover(h.grave, h.helper));
            h.grave.claim(h.helper); h.drain(); verify(h.helper, never()).giveExp(anyInt());
            FeaturePersistenceTest.await(h.access.revokeAsync(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK));
            h.grant(TrustedGraveAccess.Kind.RECOVER);
            assertTrue(h.plugin.canRecover(h.grave, h.helper)); assertFalse(h.plugin.canTrack(h.grave, h.helper));
            h.grave.claim(h.helper); h.drain(); verify(h.helper).giveExp(17); verify(h.helperInventory).setItem(4, h.cloned);
        }
    }

    @Test void ownerAndHelperAuthorizationUsesStableUuidAcrossDisplayNameChanges() throws Exception {
        try (Harness h = new Harness()) {
            when(h.owner.getName()).thenReturn("OriginalOwner");
            assertTrue(h.plugin.canTrack(h.grave, h.owner));
            assertTrue(h.plugin.canRecover(h.grave, h.owner));

            h.grant(TrustedGraveAccess.Kind.RECOVER);
            when(h.helper.getName()).thenReturn("RenamedHelper");
            assertTrue(h.plugin.canRecover(h.grave, h.helper));
            assertFalse(h.plugin.canTrack(h.grave, h.helper));

            when(h.owner.getName()).thenReturn("RenamedOwner");
            assertTrue(h.plugin.canTrack(h.grave, h.owner));
            assertTrue(h.plugin.canRecover(h.grave, h.owner));
        }
    }
    @Test void ownerAndHelperConcurrentClaimsPayOnlyWinnerOnce() throws Exception {
        try (Harness h = new Harness()) {
            h.grant(TrustedGraveAccess.Kind.RECOVER);
            h.grave.claim(h.owner); h.grave.claim(h.helper); h.grave.claim(h.owner); h.drain();
            verify(h.owner, times(1)).giveExp(17); verify(h.helper, never()).giveExp(anyInt());
            verify(h.ownerInventory, times(1)).setItem(4, h.cloned); assertTrue(h.ledger.isBlocked(h.identity()));
            assertTrue(h.grave.getGravesConfig().isDestroyed());
            verify(h.scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
        }
    }
    @Test void failedAsyncReserveDoesNotDeliverItemsXpOrRemoveGrave() throws Exception {
        try (Harness h = new Harness()) {
            h.claimDisk.fail = true; h.grave.claim(h.owner); h.drain();
            verify(h.owner, never()).giveExp(anyInt()); verify(h.ownerInventory, never()).setItem(anyInt(), any());
            verify(h.plugin, never()).removeGrave(any()); assertFalse(h.grave.getGravesConfig().isDestroyed());
        }
    }
    @Test void revocationDuringReserveIsCheckedAtFinalPayoutBoundary() throws Exception {
        try (Harness h = new Harness()) {
            h.grant(TrustedGraveAccess.Kind.RECOVER); h.grave.claim(h.helper);
            h.entityWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            FeaturePersistenceTest.await(h.access.revokeAsync(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER));
            h.drain(); verify(h.helper, never()).giveExp(anyInt()); assertFalse(h.ledger.isBlocked(h.identity()));
        }
    }
    @Test void claimAndRemovalCancellationOccurBeforePayout() throws Exception {
        try (Harness h = new Harness()) {
            doAnswer(call -> { if (call.getArgument(0) instanceof GraveRemoveEvent e) e.setCancelled(true); return null; }).when(h.events).callEvent(any());
            h.grave.claim(h.owner); h.drain(); verify(h.owner, never()).giveExp(anyInt());
            assertFalse(h.ledger.isBlocked(h.identity())); assertFalse(h.grave.getGravesConfig().isDestroyed());
        }
    }
    @Test void claimCallbacksChangeContentsAndExperienceBeforeDurableEvidence() throws Exception {
        try (Harness h = new Harness()) {
            h.grant(TrustedGraveAccess.Kind.RECOVER);
            ItemStack replacement = mock(ItemStack.class); Material material = mock(Material.class);
            when(material.isAir()).thenReturn(false); when(replacement.getType()).thenReturn(material);
            when(replacement.clone()).thenReturn(replacement);
            when(replacement.serialize()).thenReturn(Map.of("type", "GOLD_INGOT", "amount", 3));
            doAnswer(call -> { if (call.getArgument(0) instanceof GraveClaimEvent) {
                h.grave.getGravesConfig().getItems().clear(); h.grave.getGravesConfig().getItems().put(7, replacement);
                try { Field exp = GravesConfig.class.getDeclaredField("exp"); exp.setAccessible(true); exp.setInt(h.grave.getGravesConfig(), 31); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            } return null; }).when(h.events).callEvent(any());
            h.grave.claim(h.owner); h.entityWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            String status = h.ledger.status(h.identity()); assertTrue(status.startsWith("RESERVED:"));
            var evidence = new org.bukkit.configuration.file.YamlConfiguration();
            evidence.loadFromString(new String(Base64.getDecoder().decode(status.split(":", 3)[2]), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(31, evidence.getInt("experience"));
            assertEquals(Set.of("7"), evidence.getConfigurationSection("items").getKeys(false));
            assertEquals("GOLD_INGOT", evidence.getString("items.7.type")); assertEquals(3, evidence.getInt("items.7.amount"));
            h.drain(); verify(h.ownerInventory).setItem(7, replacement);
            verify(h.ownerInventory, never()).setItem(eq(4), any()); verify(h.owner).giveExp(31);
            assertTrue(h.ledger.status(h.identity()).startsWith("PAID:"));
            assertTrue(h.ledger.isBlocked(h.identity()));
        }
    }
    @Test void callbackRevocationAndDisconnectDoNotPayoutOrMarkSuccess() throws Exception {
        try (Harness h = new Harness()) {
            h.grant(TrustedGraveAccess.Kind.RECOVER);
            doAnswer(call -> { if (call.getArgument(0) instanceof GraveClaimEvent) h.access.revokeAsync(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER); return null; }).when(h.events).callEvent(any());
            h.grave.claim(h.helper); h.drain(); verify(h.helper, never()).giveExp(anyInt());
            h.grave.claim(h.owner); h.entityWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            when(h.owner.isOnline()).thenReturn(false); h.drain(); verify(h.owner, never()).giveExp(anyInt());
        }
    }
    @Test void overflowIsDroppedAtRecipientAndXpIsFullWithLegacyChance() throws Exception {
        try (Harness h = new Harness()) {
            ItemStack occupied = mock(ItemStack.class); Material type = h.stored.getType(); when(occupied.getType()).thenReturn(type);
            when(h.ownerInventory.getItem(4)).thenReturn(occupied); when(h.ownerInventory.addItem(h.cloned)).thenReturn(new HashMap<>(Map.of(0, h.cloned)));
            h.grave.claim(h.owner); h.drain(); verify(h.world).dropItem(h.owner.getLocation(), h.cloned); verify(h.owner).giveExp(17);
            assertEquals(Map.of(4, h.cloned), GravePayout.select(Map.of(4, h.stored), 100, () -> { fail("100% consumes RNG"); return 0; }));
            assertTrue(GravePayout.select(Map.of(4, h.stored), 0, () -> 0).isEmpty());
        }
    }
    @Test void selectionUsesStableIdentityAndRevocationOrRemovalInvalidatesWithoutChunks() throws Exception {
        try (Harness h = new Harness()) {
            h.grant(TrustedGraveAccess.Kind.TRACK); assertTrue(h.navigation.select(h.helper, h.grave)); assertSame(h.grave, h.navigation.target(h.helper));
            FeaturePersistenceTest.await(h.access.revokeAsync(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK));
            assertNull(h.navigation.target(h.helper));
            assertTrue(h.navigation.select(h.owner, h.grave)); h.grave.getGravesConfig().setDestroyed(true); assertNull(h.navigation.target(h.owner));
            verify(h.world, never()).getBlockAt(anyInt(), anyInt(), anyInt()); verify(h.world, never()).getChunkAt(anyInt(), anyInt());
        }
    }
    @Test void navigationShowsWorldDistanceAndVerticalSeparation() throws Exception {
        try (Harness h = new Harness()) {
            when(h.owner.getLocation()).thenReturn(new Location(h.world, 4, 60, 5));
            assertEquals("overworld, 5 blocks, above", h.grave.getNavigationMessage(h.owner));
            when(h.owner.getLocation()).thenReturn(new Location(h.world, 1, 70, 1)); assertTrue(h.grave.getNavigationMessage(h.owner).endsWith("below"));
            World other = mock(World.class); when(other.getUID()).thenReturn(UUID.randomUUID()); when(h.owner.getWorld()).thenReturn(other); when(h.owner.getLocation()).thenReturn(new Location(other, 1, 70, 1));
            assertEquals("world overworld (different world)", h.grave.getNavigationMessage(h.owner));
            verify(h.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        }
    }
    @Test void distanceAndLoreUseOnePlayerLocationSnapshotAcrossWorldChange() throws Exception {
        try (var h = new Harness()) {
            var other = mock(World.class); when(other.getUID()).thenReturn(UUID.randomUUID());
            // A split getWorld/getLocation read would compare old world to a new-world location and throw.
            when(h.owner.getWorld()).thenReturn(h.world);
            when(h.owner.getLocation()).thenReturn(new Location(other, 1, 64, 1)); clearInvocations(h.owner);
            double distance = assertDoesNotThrow(() -> h.grave.getDistance(h.owner)); assertEquals(-1D, distance);
            verify(h.owner, times(1)).getLocation(); verify(h.owner, never()).getWorld(); clearInvocations(h.owner);
            assertEquals("world overworld (different world)", h.grave.getNavigationMessage(h.owner));
            verify(h.owner, times(1)).getLocation(); verify(h.owner, never()).getWorld();
        }
    }

    @Test void legacyBreakOtherConfigPermissionAndProtectionRemainIndependentOfTrust() throws Exception {
        try (Harness h = new Harness()) {
            assertFalse(h.grave.legacyClaimAllowed(h.helper)); when(h.config.isBreakOtherGravesEnabled()).thenReturn(true);
            assertFalse(h.grave.legacyClaimAllowed(h.helper));
            when(h.config.getBreakOtherGravesTimeBeforeBreakable()).thenReturn(ParsedDuration.parse("0", java.util.concurrent.TimeUnit.MINUTES));
            assertTrue(h.grave.legacyClaimAllowed(h.helper)); when(h.config.isBreakOtherGravesRequirePermission()).thenReturn(true);
            assertFalse(h.grave.legacyClaimAllowed(h.helper)); when(h.helper.hasPermission("GraveStonesPlus.BreakOtherGraves")).thenReturn(true);
            assertTrue(h.grave.legacyClaimAllowed(h.helper)); assertFalse(h.plugin.canTrack(h.grave, h.helper));
            h.grave.claim(h.helper); h.drain(); verify(h.helper).giveExp(17);
        }
    }
    @Test void directContentViewRequiresRecoveryOrHistoricalStaffAuthority() throws Exception {
        try (Harness h = new Harness()) {
            h.grant(TrustedGraveAccess.Kind.TRACK); assertFalse(h.grave.canViewContents(h.helper));
            h.grave.openGUIWithItems(h.helper); h.drain(); verify(h.helper, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
            when(h.helper.hasPermission("GraveStonesPlus.AllBrokenGraves")).thenReturn(true);
            assertFalse(h.grave.canAdminister(h.helper)); assertFalse(h.grave.canViewContents(h.helper));
            h.grave.getGravesConfig().setDestroyed(true); assertTrue(h.grave.canViewContents(h.helper));
            h.grave.getGravesConfig().setDestroyed(false); assertFalse(h.grave.canViewContents(h.helper));
            when(h.helper.hasPermission("GraveStonesPlus.AllGraves")).thenReturn(true);
            assertTrue(h.grave.canAdminister(h.helper)); assertTrue(h.grave.canViewContents(h.helper));
        }
    }
    @Test void legacyGraveShapePreservesOwnerExperienceLocationAndExactSlotMap() throws Exception {
        try (Harness h = new Harness()) {
            h.bukkit.when(() -> Bukkit.getWorld(h.world.getUID())).thenReturn(h.world);
            HashMap<Integer, ItemStack> oldItems = new HashMap<>(Map.of(-1, h.stored, 0, h.stored));
            Map<String, Object> old = new HashMap<>();
            old.put("UUID", h.owner.getUniqueId().toString()); old.put("World", h.world.getUID().toString());
            old.put("PlayerName", "Old owner name"); old.put("X", 1); old.put("Y", 64); old.put("Z", 1);
            old.put("Time", 123L); old.put("EXP", 42); old.put("Items", oldItems);
            GravesConfig restored = GravesConfig.deserialize(old);
            assertEquals(h.owner.getUniqueId(), restored.getUuid()); assertEquals(42, restored.getExp());
            assertEquals(123L, restored.getTime()); assertEquals(oldItems, restored.getItems());
            assertEquals("PLAYER_HEAD", restored.getGraveDisplayType()); assertFalse(restored.isDestroyed());
        }
    }
    @Test void recoveryEvidencePreservesDistinctArmorSlotsAndXpAcrossRestart() throws Exception {
        try (Harness h = new Harness()) {
            HashMap<Integer, ItemStack> items = new HashMap<>(Map.of(-1, h.cloned, 0, h.cloned));
            GravesConfig record = new GravesConfig(h.owner.getUniqueId(), "Owner", h.owner.getLocation(), items, 42, "death", 123, false, 0, null, null, null);
            String payload = GraveClaimLedger.evidence(record);
            assertTrue(FeaturePersistenceTest.await(h.ledger.reserve(GraveIdentity.of(record), h.owner.getUniqueId(), payload)));
            var restarted = new GraveClaimLedger(new DurableFeatureState(h.io, h.claimDisk, 8192)); FeaturePersistenceTest.await(restarted.loaded());
            String saved = restarted.status(GraveIdentity.of(record)); assertTrue(saved.endsWith(payload));
            String yaml = new String(Base64.getDecoder().decode(payload), java.nio.charset.StandardCharsets.UTF_8);
            org.bukkit.configuration.file.YamlConfiguration evidence = new org.bukkit.configuration.file.YamlConfiguration(); evidence.loadFromString(yaml);
            assertEquals(42, evidence.getInt("experience")); assertEquals(Set.of("-1", "0"), evidence.getConfigurationSection("items").getKeys(false));
        }
    }
    @Test void actualScheduledDeadlineChangesAndDisableInvalidateEligibility() throws Exception {
        try (Harness h = new Harness()) {
            java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            when(h.plugin.getTimer()).thenReturn(timer);
            try {
                h.grave.checkTimeLimit("2"); assertEquals(h.grave.getGravesConfig().getTime() + 120_000, h.grave.getExpiryDeadline());
                h.grave.checkTimeLimit("3m"); assertEquals(h.grave.getGravesConfig().getTime() + 180_000, h.grave.getExpiryDeadline());
                h.grave.checkTimeLimit("0"); assertEquals(0, h.grave.getExpiryDeadline());
                h.grave.checkTimeLimit("invalid"); assertEquals(0, h.grave.getExpiryDeadline());
            } finally { timer.shutdownNow(); }
        }
    }

    @Test void invalidExpiryReplacementRetainsTheActualScheduledTaskAndDeadline() throws Exception {
        try (Harness h = new Harness()) {
            var timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            when(h.plugin.getTimer()).thenReturn(timer);
            try {
                h.grave.checkTimeLimit("1h"); long deadline = h.grave.getExpiryDeadline();
                Field taskField = Grave.class.getDeclaredField("expirationTask"); taskField.setAccessible(true);
                var original = (java.util.concurrent.ScheduledFuture<?>) taskField.get(h.grave);
                assertNotNull(original);
                for (String invalid : List.of("tomorrow", "999999999999999999999999", "9223372036854775807m", "9223372036854775807ms")) {
                    h.grave.checkTimeLimit(invalid);
                    assertEquals(deadline, h.grave.getExpiryDeadline());
                    assertSame(original, taskField.get(h.grave)); assertFalse(original.isCancelled());
                }
                h.grave.checkTimeLimit("2h"); assertTrue(original.isCancelled());
                assertEquals(h.grave.getGravesConfig().getTime() + 7_200_000, h.grave.getExpiryDeadline());
                var replacement = (java.util.concurrent.ScheduledFuture<?>) taskField.get(h.grave);
                h.grave.checkTimeLimit(""); assertTrue(replacement.isCancelled());
                assertEquals(0, h.grave.getExpiryDeadline());
            } finally { timer.shutdownNow(); }
        }
    }

    @Test void loginAndPeriodicRemindersSendOneUsefulSummaryOnEntityOwner() throws Exception {
        try (Harness h = new Harness()) {
            var state = new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192);
            var reminders = new GraveReminderStore(state); FeaturePersistenceTest.await(reminders.loaded());
            when(h.plugin.getGraveReminderStore()).thenReturn(reminders);
            when(h.config.isBreakOtherGravesEnabled()).thenReturn(true);
            when(h.config.getProtectionReminderMillis()).thenReturn(60_000L); when(h.config.getExpiryReminderMillis()).thenReturn(60_000L);
            java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(); when(h.plugin.getTimer()).thenReturn(timer);
            var listener = new com.bencodez.gravestonesplus.listeners.GraveReminderListener(h.plugin);
            try {
                h.grave.checkTimeLimit("1m"); listener.start();
                verify(h.platform).runTimer(any(Runnable.class), eq(20L), eq(100L));
                listener.onJoin(new org.bukkit.event.player.PlayerJoinEvent(h.owner, "")); h.drain();
                verify(h.owner, times(1)).sendMessage(argThat((String text) -> text.contains("protection ends") && text.contains("expires")));
                listener.checkPlayer(h.owner); h.drain(); assertEquals(2, state.snapshot().size());
                verify(h.owner, times(1)).sendMessage(any(String.class));
                verify(h.scheduler, never()).runTask(any(Plugin.class), any(Runnable.class));
            } finally { listener.stop(); timer.shutdownNow(); }
        }
    }
    @Test void offlineJoinDoesNotMarkDeadlinesAndNextOnlineCheckCanSend() throws Exception {
        try (Harness h = new Harness()) {
            var state = new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192);
            var reminders = new GraveReminderStore(state); FeaturePersistenceTest.await(reminders.loaded());
            when(h.plugin.getGraveReminderStore()).thenReturn(reminders);
            when(h.config.isBreakOtherGravesEnabled()).thenReturn(true); when(h.config.getProtectionReminderMillis()).thenReturn(60_000L);
            var listener = new com.bencodez.gravestonesplus.listeners.GraveReminderListener(h.plugin); listener.start();
            try {
                when(h.owner.isOnline()).thenReturn(false); listener.onJoin(new org.bukkit.event.player.PlayerJoinEvent(h.owner, "")); h.drain();
                assertTrue(state.snapshot().isEmpty()); verify(h.owner, never()).sendMessage(any(String.class));
                when(h.owner.isOnline()).thenReturn(true); listener.onJoin(new org.bukkit.event.player.PlayerJoinEvent(h.owner, "")); h.drain();
                assertEquals(1, state.snapshot().size()); verify(h.owner, times(1)).sendMessage(any(String.class));
            } finally { listener.stop(); }
        }
    }

    @Test void cancelledExpirationRestoresOwnerActionsAndRespectsRescheduledDeadline() throws Exception {
        try (Harness h = new Harness()) {
            when(h.plugin.getTimer()).thenReturn(mock(java.util.concurrent.ScheduledExecutorService.class));
            doAnswer(call -> { if (call.getArgument(0) instanceof GraveRemoveEvent e) e.setCancelled(true); return null; }).when(h.events).callEvent(any());
            h.grave.checkTimeLimit(1); // public API: elapsed deadlines can be set by an integration
            java.lang.reflect.Field deadline = Grave.class.getDeclaredField("expiryDeadline"); deadline.setAccessible(true); deadline.setLong(h.grave, System.currentTimeMillis() - 1);
            assertFalse(h.plugin.isLiveGrave(h.grave));
            h.grave.removeGrave(GraveRemoveReason.EXPIRED); h.regionWork.remove().run();
            assertEquals(0, h.grave.getExpiryDeadline()); assertTrue(h.plugin.isLiveGrave(h.grave)); assertTrue(h.plugin.canTrack(h.grave, h.owner));
            long replacement = System.currentTimeMillis() + 60_000;
            doAnswer(call -> { if (call.getArgument(0) instanceof GraveRemoveEvent e) { deadline.setLong(h.grave, replacement); e.setCancelled(true); } return null; }).when(h.events).callEvent(any());
            deadline.setLong(h.grave, System.currentTimeMillis() - 1); h.grave.removeGrave(GraveRemoveReason.EXPIRED); h.regionWork.remove().run();
            assertEquals(replacement, h.grave.getExpiryDeadline()); assertTrue(h.plugin.isLiveGrave(h.grave));
        }
    }
    @Test void saturatedMysqlAdmissionRetainsLatestDeathSnapshotForDrainAndShutdown() throws Exception {
        try (Harness h = new Harness()) {
            FeatureIO small = new FeatureIO(1); when(h.plugin.getFeatureIO()).thenReturn(small);
            when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of());
            var backend = mock(com.bencodez.gravestonesplus.storage.GraveStorageBackend.class);
            var manager = new com.bencodez.gravestonesplus.storage.GraveStorageManager(h.plugin);
            java.lang.reflect.Field backendField = manager.getClass().getDeclaredField("backend"); backendField.setAccessible(true); backendField.set(manager, backend);
            java.lang.reflect.Field typeField = manager.getClass().getDeclaredField("type"); typeField.setAccessible(true); typeField.set(manager, com.bencodez.gravestonesplus.storage.DataStorageType.MYSQL);
            var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
            var busy = small.submit(() -> { entered.countDown(); try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); } return true; });
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)); var queued = small.submit(() -> true);
            manager.upsertGrave(h.grave.getGravesConfig(), false); verify(backend, never()).upsertGrave(any(), anyBoolean());
            release.countDown(); FeaturePersistenceTest.await(busy); FeaturePersistenceTest.await(queued); small.close();
            org.mockito.ArgumentCaptor<GravesConfig> written = org.mockito.ArgumentCaptor.forClass(GravesConfig.class);
            verify(backend).upsertGrave(written.capture(), eq(false)); assertEquals(h.grave.getGravesConfig().getUuid(), written.getValue().getUuid());
            assertEquals(17, written.getValue().getExp()); assertEquals(Set.of(4), written.getValue().getItems().keySet());
        }
    }

    @Test void retiredRecipientCancelsUnpaidReservationForBothRetirementPaths() throws Exception {
        for (boolean immediatelyRetired : List.of(true, false)) try (Harness h = new Harness()) {
            doAnswer(call -> {
                Runnable retired = call.getArgument(2);
                if (!immediatelyRetired) retired.run();
                return java.util.concurrent.CompletableFuture.completedFuture(immediatelyRetired
                        ? com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SCHEDULER_RETIRED
                        : com.bencodez.simpleapi.folialib.enums.EntityTaskResult.ENTITY_RETIRED);
            }).when(h.platform).runAtEntityWithFallback(any(Entity.class), any(), any(Runnable.class));
            h.grave.claim(h.owner); h.drain();
            verify(h.owner, never()).giveExp(anyInt()); assertFalse(h.ledger.isBlocked(h.identity()));
            doAnswer(call -> { java.util.function.Consumer<com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask> work = call.getArgument(1);
                h.entityWork.add(() -> work.accept(null)); return new java.util.concurrent.CompletableFuture<>();
            }).when(h.platform).runAtEntityWithFallback(any(Entity.class), any(), any(Runnable.class));
            h.grave.claim(h.owner); h.drain(); verify(h.owner).giveExp(17);
        }
    }
    @Test void expirationOverlappingReservationRetriesAfterCancelledPayout() throws Exception {
        try (Harness h = new Harness()) {
            // Static Bukkit mocks are thread-local. This cancellation continuation
            // runs on the storage worker, which must report that it is not an owner.
            var workerBukkit = FeaturePersistenceTest.await(h.io.submit(() -> {
                var context = mockStatic(Bukkit.class);
                context.when(Bukkit::isPrimaryThread).thenReturn(false);
                return context;
            }));
            try {
            h.grave.claim(h.owner); h.entityWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            Field deadline = Grave.class.getDeclaredField("expiryDeadline"); deadline.setAccessible(true);
            deadline.setLong(h.grave, System.currentTimeMillis() - 1);
            h.grave.removeGrave(GraveRemoveReason.EXPIRED); h.regionWork.remove().run();
            assertFalse(h.grave.getGravesConfig().isDestroyed()); h.drain();
            assertFalse(h.ledger.isBlocked(h.identity())); verify(h.owner, never()).giveExp(anyInt());
            h.regionWork.remove().run(); assertTrue(h.grave.getGravesConfig().isDestroyed());
            } finally { FeaturePersistenceTest.await(h.io.submit(() -> { workerBukkit.close(); return null; })); }
        }
    }

    @Test void publicRemovalOnBukkitOwnerPublishesStateAndEventBeforeReturning() throws Exception {
        try (Harness h = new Harness()) {
            groundWorld(h); h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            h.grave.removeGrave(GraveRemoveReason.MANUAL_REMOVE);
            assertTrue(h.grave.isRemove()); assertTrue(h.grave.getGravesConfig().isDestroyed());
            assertTrue(h.active.isEmpty()); verify(h.events).callEvent(any(GraveRemoveEvent.class));
            verify(h.plugin).removeGrave(h.grave);
            // Preserve the native API's deferred marker cleanup independently of state publication.
            assertEquals(1, h.regionWork.size());
            verify(h.world.getBlockAt(h.grave.getGravesConfig().getLocation()), never()).setType(any());
            h.regionWork.remove().run();
            verify(h.world.getBlockAt(h.grave.getGravesConfig().getLocation())).setType(Material.AIR);
        }
    }
    @Test void publicRemovalOnBukkitOwnerHonorsCancellationBeforeReturning() throws Exception {
        try (Harness h = new Harness()) {
            h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            doAnswer(call -> { ((GraveRemoveEvent) call.getArgument(0)).setCancelled(true); return null; })
                    .when(h.events).callEvent(any(GraveRemoveEvent.class));
            h.grave.removeGrave(GraveRemoveReason.MANUAL_REMOVE);
            assertFalse(h.grave.isRemove()); assertFalse(h.grave.getGravesConfig().isDestroyed());
            assertTrue(h.regionWork.isEmpty()); verify(h.plugin, never()).removeGrave(any());
        }
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void publicRemovalOnFoliaAlwaysHandsOffToTheMarkerRegion(boolean primary) throws Exception {
        try (Harness h = new Harness()) {
            h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(primary); when(h.folia.isFolia()).thenReturn(true);
            h.grave.removeGrave(GraveRemoveReason.MANUAL_REMOVE);
            assertFalse(h.grave.isRemove()); assertEquals(1, h.regionWork.size());
            verify(h.events, never()).callEvent(any()); verify(h.plugin, never()).removeGrave(any());
            h.regionWork.remove().run();
            assertTrue(h.grave.isRemove()); verify(h.events).callEvent(any(GraveRemoveEvent.class));
            verify(h.plugin).removeGrave(h.grave);
        }
    }

    @Test void ownerGrantRejectsRemovedGraveAndRevokedPermissionAtAdmission() throws Exception {
        try (Harness h = new Harness()) {
            assertFalse(FeaturePersistenceTest.await(h.grave.grantHelper(h.owner, h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER, System.currentTimeMillis() + 60_000)));
            when(h.owner.hasPermission("GraveStonesPlus.Trust")).thenReturn(true);
            assertTrue(FeaturePersistenceTest.await(h.grave.grantHelper(h.owner, h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER, System.currentTimeMillis() + 60_000)));
            assertFalse(FeaturePersistenceTest.await(h.grave.revokeHelper(h.owner, h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER)));
            when(h.owner.hasPermission("GraveStonesPlus.Trust.Revoke")).thenReturn(true);
            assertTrue(FeaturePersistenceTest.await(h.grave.revokeHelper(h.owner, h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER)));
            h.grave.removeGrave(GraveRemoveReason.MANUAL_REMOVE); h.regionWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            assertFalse(FeaturePersistenceTest.await(h.grave.grantHelper(h.owner, h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER, System.currentTimeMillis() + 60_000)));
            assertFalse(h.access.allows(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.RECOVER));
        }
    }
    @Test void playerRemovalRechecksPermissionAndCancellationBeforeAdvertisingSuccess() throws Exception {
        try (Harness h = new Harness()) {
            when(h.helper.hasPermission("GraveStonesPlus.Admin")).thenReturn(true);
            h.grave.removeByPlayer(h.helper, GraveRemoveReason.MANUAL_REMOVE);
            when(h.helper.hasPermission("GraveStonesPlus.Admin")).thenReturn(false); h.drain();
            assertFalse(h.grave.getGravesConfig().isDestroyed()); verify(h.helper, never()).sendMessage(any(String.class));
            doAnswer(call -> { if (call.getArgument(0) instanceof GraveRemoveEvent event) event.setCancelled(true); return null; }).when(h.events).callEvent(any());
            h.grave.removeByPlayer(h.owner, GraveRemoveReason.MANUAL_REMOVE); h.drain();
            assertFalse(h.grave.getGravesConfig().isDestroyed()); verify(h.owner, never()).sendMessage(any(String.class));
        }
    }

    @Test void expirationRechecksExtendedOrDisabledDeadlineBeforeAndAfterEvent() throws Exception {
        for (boolean duringEvent : List.of(false, true)) for (long replacement : List.of(0L, System.currentTimeMillis() + 60_000)) try (Harness h = new Harness()) {
            Field deadline = Grave.class.getDeclaredField("expiryDeadline"); deadline.setAccessible(true);
            deadline.setLong(h.grave, System.currentTimeMillis() - 1);
            h.grave.removeGrave(GraveRemoveReason.EXPIRED);
            if (duringEvent) doAnswer(call -> { if (call.getArgument(0) instanceof GraveRemoveEvent) deadline.setLong(h.grave, replacement); return null; }).when(h.events).callEvent(any());
            else deadline.setLong(h.grave, replacement);
            h.regionWork.remove().run(); assertFalse(h.grave.getGravesConfig().isDestroyed()); assertTrue(h.plugin.isLiveGrave(h.grave));
            verify(h.plugin, never()).removeGrave(any());
        }
    }
    @Test void sameProcessRecreationOfExpiredUnclaimedGraveRestoresAccessAndPayout() throws Exception {
        try (Harness h = new Harness()) {
            Field deadline = Grave.class.getDeclaredField("expiryDeadline"); deadline.setAccessible(true); deadline.setLong(h.grave, System.currentTimeMillis() - 1);
            h.grave.removeGrave(GraveRemoveReason.EXPIRED); h.regionWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            assertTrue(h.grave.getGravesConfig().isDestroyed()); assertFalse(h.plugin.isLiveGrave(h.grave));
            var storage = mock(com.bencodez.gravestonesplus.storage.GraveStorageManager.class);
            when(storage.saveSnapshotFromObjects(any(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
            when(storage.saveRecreatedGrave(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
            field(h.plugin, "storageManager", storage); field(h.plugin, "brokenGraves", Collections.synchronizedList(new ArrayList<>(List.of(h.grave))));
            doCallRealMethod().when(h.plugin).recreateBrokenGrave(any());
            doCallRealMethod().when(h.plugin).recreateBrokenGrave(any(), any());
            doCallRealMethod().when(h.plugin).recreateBrokenGrave(any(), any(), anyBoolean());
            h.plugin.recreateBrokenGrave(h.grave); h.drain(); h.regionWork.remove().run();
            assertEquals(0, h.grave.getExpiryDeadline()); assertTrue(h.plugin.canTrack(h.grave, h.owner));
            h.grave.claim(h.owner); h.drain(); verify(h.owner).giveExp(17);
        }
    }

    @Test void legacyCreateBrokenGraveUsesAuthoritativeWrapperAndCreatesMarker() throws Exception {
        try (Harness h = new Harness()) {
            Field deadline = Grave.class.getDeclaredField("expiryDeadline"); deadline.setAccessible(true);
            deadline.setLong(h.grave, System.currentTimeMillis() - 1);
            h.grave.removeGrave(GraveRemoveReason.EXPIRED); h.regionWork.remove().run(); h.drain();
            assertTrue(h.grave.getGravesConfig().isDestroyed()); assertTrue(h.active.isEmpty());
            var storage = mock(com.bencodez.gravestonesplus.storage.GraveStorageManager.class);
            field(h.plugin, "storageManager", storage);
            var broken = Collections.synchronizedList(new ArrayList<>(List.of(h.grave)));
            field(h.plugin, "brokenGraves", broken);
            doCallRealMethod().when(h.plugin).recreateBrokenGrave(any(), any(), anyBoolean());

            h.grave.createBrokenGrave(); h.drain();
            assertEquals(1, h.regionWork.size());
            h.regionWork.remove().run(); h.drain();

            assertFalse(h.grave.getGravesConfig().isDestroyed());
            assertEquals(List.of(h.grave), h.active); assertTrue(broken.isEmpty());
            assertEquals(0, h.grave.getExpiryDeadline());
            verify(storage).saveSnapshotFromObjects(h.active, broken);
        }
    }

    @Test void recreationAcknowledgesPublicationInsteadOfAdmission() throws Exception {
        for (boolean mysql : List.of(false, true)) for (boolean cancel : List.of(false, true)) try (Harness h = new Harness()) {
            Field deadline = Grave.class.getDeclaredField("expiryDeadline"); deadline.setAccessible(true);
            deadline.setLong(h.grave, System.currentTimeMillis() - 1);
            h.grave.removeGrave(GraveRemoveReason.EXPIRED); h.regionWork.remove().run(); h.drain();
            var publication = new java.util.concurrent.CompletableFuture<Boolean>();
            var storage = mock(com.bencodez.gravestonesplus.storage.GraveStorageManager.class);
            when(storage.isMySQL()).thenReturn(mysql);
            when(storage.saveSnapshotFromObjects(any(), any())).thenReturn(publication);
            when(storage.saveRecreatedGrave(any())).thenReturn(publication);
            var broken = Collections.synchronizedList(new ArrayList<>(List.of(h.grave)));
            field(h.plugin, "storageManager", storage); field(h.plugin, "brokenGraves", broken);
            doCallRealMethod().when(h.plugin).recreateBrokenGrave(any(), any());
            doCallRealMethod().when(h.plugin).recreateBrokenGrave(any(), any(), anyBoolean());

            var observer = h.plugin.recreateBrokenGrave(h.grave, null); h.drain();
            h.regionWork.remove().run();
            assertEquals(List.of(h.grave), h.active); assertTrue(broken.isEmpty());
            if (mysql) verify(storage).saveRecreatedGrave(h.grave.getGravesConfig());
            else verify(storage).saveSnapshotFromObjects(h.active, broken);
            assertFalse(observer.isDone());
            if (cancel) assertTrue(observer.cancel(true));
            assertFalse(publication.isCancelled());
            publication.complete(true);
            if (!cancel) assertTrue(FeaturePersistenceTest.await(observer));
            assertFalse(h.grave.getGravesConfig().isDestroyed());
            verify(h.owner, never()).giveExp(anyInt());
        }
    }

    @Test void cancellingRecreationObserverCannotSkipCommittedListAndBackendTransfer() throws Exception {
        assertRecreationObserverIsolation(observer -> assertTrue(observer.cancel(true)), true);
    }

    @Test void externallyCompletingRecreationObserverCannotSkipCommittedListAndBackendTransfer() throws Exception {
        for (boolean suppliedResult : List.of(false, true))
            assertRecreationObserverIsolation(observer -> assertTrue(observer.complete(suppliedResult)), true);
    }

    @Test void exceptionallyCompletingRecreationObserverCannotSkipCommittedListAndBackendTransfer() throws Exception {
        assertRecreationObserverIsolation(observer ->
                assertTrue(observer.completeExceptionally(new IllegalStateException("caller completion"))), true);
    }

    @Test void cancellingCompletedRecreationObserverCannotUndoListAndBackendTransfer() throws Exception {
        assertRecreationObserverIsolation(observer -> assertFalse(observer.cancel(true)), false);
    }

    private void assertRecreationObserverIsolation(
            java.util.function.Consumer<java.util.concurrent.CompletableFuture<Boolean>> interfere,
            boolean beforeCommit) throws Exception {
        for (boolean mysql : List.of(false, true)) try (Harness h = new Harness()) {
            Field deadline = Grave.class.getDeclaredField("expiryDeadline"); deadline.setAccessible(true);
            deadline.setLong(h.grave, System.currentTimeMillis() - 1);
            h.grave.removeGrave(GraveRemoveReason.EXPIRED); h.regionWork.remove().run(); h.drain();
            assertTrue(h.grave.getGravesConfig().isDestroyed()); assertTrue(h.active.isEmpty());
            var storage = mock(com.bencodez.gravestonesplus.storage.GraveStorageManager.class);
            when(storage.isMySQL()).thenReturn(mysql);
            when(storage.saveSnapshotFromObjects(any(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
            when(storage.saveRecreatedGrave(any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(true));
            List<Grave> broken = Collections.synchronizedList(new ArrayList<>(List.of(h.grave)));
            field(h.plugin, "storageManager", storage); field(h.plugin, "brokenGraves", broken);
            doCallRealMethod().when(h.plugin).recreateBrokenGrave(any(), any());
            doCallRealMethod().when(h.plugin).recreateBrokenGrave(any(), any(), anyBoolean());

            var observer = h.plugin.recreateBrokenGrave(h.grave, null); h.drain();
            assertFalse(observer.isDone()); assertEquals(1, h.regionWork.size());
            assertTrue(h.grave.getGravesConfig().isDestroyed()); assertTrue(h.active.isEmpty());
            assertEquals(List.of(h.grave), broken); verifyNoInteractions(storage);
            if (beforeCommit) interfere.accept(observer);
            h.regionWork.remove().run();
            if (!beforeCommit) {
                assertTrue(FeaturePersistenceTest.await(observer));
                interfere.accept(observer);
            }

            assertFalse(h.grave.getGravesConfig().isDestroyed()); assertEquals(0, h.grave.getExpiryDeadline());
            assertEquals(List.of(h.grave), h.active); assertTrue(broken.isEmpty());
            assertTrue(h.plugin.canTrack(h.grave, h.owner));
            if (mysql) {
                verify(storage).saveRecreatedGrave(h.grave.getGravesConfig());
                verify(storage, never()).saveGravesFromObjects(any());
                verify(storage, never()).saveBrokenGravesFromObjects(any());
            } else {
                verify(storage).saveSnapshotFromObjects(h.active, broken);
                verify(storage, never()).setBroken(any(), anyBoolean());
            }
            verify(h.owner, never()).giveExp(anyInt()); verify(h.helper, never()).giveExp(anyInt());
        }
    }

    @Test void nonOwnerSuccessMessagePreservesConfiguredBreakingOtherSwitch() throws Exception {
        for (boolean enabled : List.of(false, true)) try (Harness h = new Harness()) {
            h.grant(TrustedGraveAccess.Kind.RECOVER); when(h.config.isBreakOtherGravesSendMessage()).thenReturn(enabled);
            h.grave.claim(h.helper); h.drain(); verify(h.helper).giveExp(17);
            verify(h.helper, times(enabled ? 1 : 0)).sendMessage("Recovered Owner");
        }
    }

    @Test void legacyGroundDropOnlyDropsCapturedItemsWithoutChangingGraveOrExperience() throws Exception {
        try (Harness h = new Harness()) {
            groundWorld(h);
            h.grave.dropItemsOnGround(h.owner);
            assertEquals(1, h.regionWork.size());
            // Change the mutable source after scheduling: the accepted drop owns its snapshot.
            h.grave.getGravesConfig().getItems().clear();
            h.regionWork.remove().run();
            verify(h.world).dropItem(eq(h.grave.getGravesConfig().getLocation()), same(h.cloned));
            verify(h.world, never()).spawn(any(Location.class), eq(ExperienceOrb.class));
            verify(h.owner, never()).giveExp(anyInt()); verify(h.plugin, never()).removeGrave(any());
            verify(h.events, never()).callEvent(any());
            assertTrue(h.plugin.isLiveGrave(h.grave)); assertFalse(h.grave.isLimitPayoutPending());
            assertFalse(h.grave.getGravesConfig().isDestroyed()); assertEquals(17, h.grave.getGravesConfig().getExp());
            assertNull(h.ledger.status(h.identity())); assertTrue(h.claimDisk.values.isEmpty());
        }
    }
    @Test void legacyGroundDropWorksWithoutRecoveryInfrastructureAndCanBeUsedOnAHistoricalRecord() throws Exception {
        try (Harness h = new Harness()) {
            groundWorld(h); h.grave.getGravesConfig().setDestroyed(true);
            when(h.plugin.getGraveClaimLedger()).thenReturn(null); when(h.plugin.getMarkerCleanup()).thenReturn(null);
            h.grave.dropItemsOnGround(null); h.regionWork.remove().run();
            verify(h.world).dropItem(any(Location.class), same(h.cloned));
            verify(h.world, never()).spawn(any(Location.class), eq(ExperienceOrb.class));
            verify(h.plugin, never()).removeGrave(any()); assertEquals(1, h.grave.getGravesConfig().getItems().size());
        }
    }

    static ExperienceOrb groundWorld(Harness h) {
        when(h.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        org.bukkit.block.Block marker = mock(org.bukkit.block.Block.class);
        var markerChunk = mock(org.bukkit.Chunk.class);
        when(marker.getChunk()).thenReturn(markerChunk);
        when(markerChunk.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
        var material = new java.util.concurrent.atomic.AtomicReference<>(Material.PLAYER_HEAD);
        when(marker.getType()).thenAnswer(call -> material.get());
        doAnswer(call -> { material.set(call.getArgument(0)); return null; }).when(marker).setType(any(Material.class));
        Grave markerOwner = h.active.isEmpty() ? h.grave : h.active.getFirst();
        Map<String, List<org.bukkit.metadata.MetadataValue>> metadata = new HashMap<>();
        metadata.put("Grave", List.of(new org.bukkit.metadata.FixedMetadataValue(h.plugin, markerOwner)));
        when(marker.getMetadata(anyString())).thenAnswer(call -> metadata.getOrDefault(call.getArgument(0), List.of()));
        doAnswer(call -> { metadata.put(call.getArgument(0), List.of(call.getArgument(1, org.bukkit.metadata.MetadataValue.class))); return null; })
                .when(marker).setMetadata(anyString(), any(org.bukkit.metadata.MetadataValue.class));
        doAnswer(call -> {
            String key = call.getArgument(0); Plugin owner = call.getArgument(1);
            metadata.computeIfPresent(key, (ignored, values) -> values.stream().filter(value -> value.getOwningPlugin() != owner).toList());
            return null;
        }).when(marker).removeMetadata(anyString(), any(Plugin.class));
        when(marker.getWorld()).thenReturn(h.world);
        when(marker.getLocation()).thenAnswer(call -> markerOwner.getGravesConfig().getLocation().clone());
        when(h.world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(marker);
        // Location.getBlock uses this overload on the bundled Spigot API.
        when(h.world.getBlockAt(any(Location.class))).thenReturn(marker);
        ExperienceOrb orb = mock(ExperienceOrb.class); when(h.world.spawn(any(Location.class), eq(ExperienceOrb.class))).thenReturn(orb);
        return orb;
    }
    static GraveRestoration limitRestoration(Harness h) {
        var backend = mock(com.bencodez.gravestonesplus.storage.GraveStorageManager.class);
        when(backend.loadGraves()).thenReturn(List.of()); when(backend.loadBrokenGraves()).thenReturn(List.of());
        when(h.plugin.getFeatureIO()).thenReturn(h.io);
        var restoration = new GraveRestoration(h.plugin, backend); when(h.plugin.getGraveRestoration()).thenReturn(restoration);
        return restoration;
    }
    static void drainGround(Harness h) throws Exception {
        for (int i = 0; i < 6; i++) {
            h.drain(); Runnable task; int bound = 0;
            while ((task = h.regionWork.poll()) != null) { assertTrue(++bound <= 32); task.run(); }
        }
        h.drain();
    }
    @Test void queuedLimitGroundPayoutAndOwnerHelperClaimsHaveOneWinnerAndFullXp() throws Exception {
        try (Harness h = new Harness()) {
            limitRestoration(h);
            ExperienceOrb orb = groundWorld(h); h.grant(TrustedGraveAccess.Kind.RECOVER);
            h.grave.dropAndRemoveForLimit(h.owner); h.grave.claim(h.helper); h.grave.claim(h.owner); h.grave.dropAndRemoveForLimit(h.owner);
            h.drain(); verify(h.world, never()).dropItem(any(), any());
            drainGround(h);
            verify(h.world, times(1)).dropItem(h.grave.getGravesConfig().getLocation(), h.cloned); verify(orb).setExperience(17);
            verify(h.owner, never()).giveExp(anyInt()); verify(h.helper, never()).giveExp(anyInt());
            assertEquals(Material.AIR, h.world.getBlockAt(h.grave.getGravesConfig().getLocation()).getType());
            assertTrue(h.world.getBlockAt(h.grave.getGravesConfig().getLocation()).getMetadata("Grave").isEmpty());
            assertTrue(h.grave.getGravesConfig().isDestroyed()); assertTrue(h.ledger.status(h.identity()).startsWith("PAID:"));
            h.grave.dropAndRemoveForLimit(h.owner); h.grave.claim(h.helper); h.drain(); verify(h.world, times(1)).dropItem(any(), any());
        }
    }
    @Test void failedLimitReservationLeavesGraveContentsAndWorldUntouched() throws Exception {
        try (Harness h = new Harness()) {
            limitRestoration(h);
            h.claimDisk.fail = true; h.grave.dropAndRemoveForLimit(h.owner); h.drain();
            assertTrue(h.regionWork.isEmpty()); verify(h.world, never()).dropItem(any(), any()); verify(h.world, never()).spawn(any(Location.class), eq(ExperienceOrb.class));
            verify(h.plugin, never()).removeGrave(any()); assertFalse(h.grave.getGravesConfig().isDestroyed());
        }
    }
    @Test void cancelledGroundPayoutUnlocksButUnloadedPayoutRetainsDurableRetryWithoutLoadingChunks() throws Exception {
        for (int mode : List.of(0, 1, 2)) try (Harness h = new Harness()) {
            limitRestoration(h);
            if (mode != 1) { groundWorld(h); doAnswer(call -> {
                if (call.getArgument(0) instanceof GraveRemoveEvent event) {
                    if (mode == 0) event.setCancelled(true); else when(h.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
                } return null;
            }).when(h.events).callEvent(any()); }
            h.grave.dropAndRemoveForLimit(h.owner); h.drain(); drainGround(h);
            assertEquals(mode != 0, h.ledger.isBlocked(h.identity()));
            if (mode != 0) assertTrue(h.ledger.status(h.identity()).startsWith("QUEUED_LIMIT:"));
            assertFalse(h.grave.getGravesConfig().isDestroyed());
            verify(h.world, never()).dropItem(any(), any()); verify(h.world, never()).getChunkAt(anyInt(), anyInt());
        }
    }
    @Test void partialGroundPayoutRetainsEvidenceAndBlocksHelperReplayAfterRestart() throws Exception {
        try (Harness h = new Harness()) {
            limitRestoration(h);
            groundWorld(h); when(h.world.spawn(any(Location.class), eq(ExperienceOrb.class))).thenThrow(new IllegalStateException("spawn failed"));
            h.grant(TrustedGraveAccess.Kind.RECOVER); h.grave.dropAndRemoveForLimit(h.owner); h.drain(); drainGround(h);
            assertTrue(h.ledger.status(h.identity()).startsWith("RESERVED:")); assertFalse(h.grave.getGravesConfig().isDestroyed());
            h.grave.claim(h.helper); h.grave.dropAndRemoveForLimit(h.owner); h.drain(); verify(h.world, times(1)).dropItem(any(), any()); verify(h.helper, never()).giveExp(anyInt());
            var restart = new GraveClaimLedger(new DurableFeatureState(h.io, h.claimDisk, 8192)); FeaturePersistenceTest.await(restart.loaded()); assertTrue(restart.isBlocked(h.identity()));
        }
    }

    @Test void unloadedLimitPayoutRetriesOnChunkLoadAndDoesNotSelectTheSameOldestAgain() throws Exception {
        try (Harness h = new Harness()) {
            ExperienceOrb orb = groundWorld(h); when(h.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(false);
            var restoration = limitRestoration(h);
            when(h.plugin.getOldestGrave(any())).thenCallRealMethod();
            h.grave.dropAndRemoveForLimit(h.owner); drainGround(h);
            assertTrue(h.grave.isLimitPayoutPending()); assertNull(h.plugin.getOldestGrave(h.owner.getUniqueId()));
            assertTrue(h.ledger.status(h.identity()).startsWith("QUEUED_LIMIT:")); verify(h.world, never()).dropItem(any(), any());
            h.grave.dropAndRemoveForLimit(h.owner); h.grave.claim(h.owner); drainGround(h);
            when(h.world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
            restoration.onChunk(h.world.getUID(), 0, 0); drainGround(h);
            verify(h.world, times(1)).dropItem(any(), eq(h.cloned)); verify(orb).setExperience(17);
            assertTrue(h.grave.isRemove()); assertTrue(h.ledger.status(h.identity()).startsWith("PAID:"));
            restoration.onChunk(h.world.getUID(), 0, 0); drainGround(h); verify(h.world, times(1)).dropItem(any(), any());
            verify(h.world, never()).getChunkAt(anyInt(), anyInt()); restoration.retire();
        }
    }
    @Test void queuedLimitStateSurvivesRestartAndCannotBecomeAReplayableReservation() throws Exception {
        try (Harness h = new Harness()) {
            var restoration = limitRestoration(h); h.grave.dropAndRemoveForLimit(h.owner); drainGround(h); restoration.retire();
            var restartedLedger = new GraveClaimLedger(new DurableFeatureState(h.io, h.claimDisk, 8192));
            assertTrue(FeaturePersistenceTest.await(restartedLedger.loaded()));
            assertEquals(GraveClaimLedger.Restoration.QUEUED_LIMIT, FeaturePersistenceTest.await(restartedLedger.restoration(h.identity())));
            when(h.plugin.getGraveClaimLedger()).thenReturn(restartedLedger);
            h.active.clear(); var backend = mock(com.bencodez.gravestonesplus.storage.GraveStorageManager.class);
            when(backend.loadGraves()).thenReturn(List.of(h.grave.getGravesConfig())); when(backend.loadBrokenGraves()).thenReturn(List.of());
            doAnswer(i -> { h.active.add(i.getArgument(0)); return null; }).when(h.plugin).restoreActiveRecord(any());
            var restarted = new GraveRestoration(h.plugin, backend); when(h.plugin.getGraveRestoration()).thenReturn(restarted);
            retryOnOwner(h, restarted); drainGround(h); assertEquals(1, h.active.size()); assertTrue(h.active.getFirst().isLimitPayoutPending());
            assertTrue(restarted.hasPending(h.identity())); verify(h.world, never()).dropItem(any(), any());
            ExperienceOrb orb = groundWorld(h); restarted.onChunk(h.world.getUID(), 0, 0); drainGround(h);
            verify(h.world, times(1)).dropItem(any(), eq(h.cloned)); verify(orb).setExperience(17);
            assertEquals(GraveClaimLedger.Restoration.PAID, FeaturePersistenceTest.await(restartedLedger.restoration(h.identity())));
            assertFalse(FeaturePersistenceTest.await(restartedLedger.reserveQueuedLimit(h.identity(), h.owner.getUniqueId())));
            assertFalse(FeaturePersistenceTest.await(restartedLedger.deferLimitReservation(h.identity(), h.owner.getUniqueId())));
            restarted.retire();
        }
    }

    @Test void manualRemovalCancelsOnlyUnstartedLimitPayout() throws Exception {
        try (Harness h = new Harness()) {
            var restoration = limitRestoration(h); h.grave.dropAndRemoveForLimit(h.owner); drainGround(h);
            assertTrue(h.grave.isLimitPayoutPending());
            h.grave.removeGrave(GraveRemoveReason.MANUAL_REMOVE); drainGround(h);
            assertTrue(h.grave.isRemove()); assertFalse(h.grave.isLimitPayoutPending());
            assertNull(h.ledger.status(h.identity())); verify(h.world, never()).dropItem(any(), any());
            restoration.retire();
        }
    }

    @Test void nearbyRecoveryRechecksMenuAndCommandConditionsAfterReservation() throws Exception {
        for (String change : List.of("distance", "world", "permission")) try (Harness h = new Harness()) {
            when(h.owner.hasPermission("GraveStonesPlus.ClaimGrave")).thenReturn(true);
            when(h.config.getGraveClaimDistance()).thenReturn(10);
            h.grave.claimNearby(h.owner); h.entityWork.remove().run(); FeaturePersistenceTest.await(h.io.barrier());
            assertFalse(h.entityWork.isEmpty()); assertTrue(h.ledger.isBlocked(h.identity()));
            if (change.equals("distance")) when(h.owner.getLocation()).thenReturn(new Location(h.world, 50, 64, 1));
            if (change.equals("world")) { World other=mock(World.class);when(other.getUID()).thenReturn(UUID.randomUUID());when(h.owner.getWorld()).thenReturn(other); when(h.owner.getLocation()).thenReturn(new Location(other, 1, 70, 1)); }
            if (change.equals("permission")) when(h.owner.hasPermission("GraveStonesPlus.ClaimGrave")).thenReturn(false);
            h.drain(); verify(h.owner,never()).giveExp(anyInt()); verify(h.ownerInventory,never()).setItem(anyInt(),any());
            assertFalse(h.ledger.isBlocked(h.identity())); assertFalse(h.grave.getGravesConfig().isDestroyed());
            when(h.owner.getWorld()).thenReturn(h.world);when(h.owner.getLocation()).thenReturn(new Location(h.world,1,64,1));
            when(h.owner.hasPermission("GraveStonesPlus.ClaimGrave")).thenReturn(true);
            h.grave.claimNearby(h.owner);h.drain();verify(h.owner).giveExp(17);
        }
    }
}
