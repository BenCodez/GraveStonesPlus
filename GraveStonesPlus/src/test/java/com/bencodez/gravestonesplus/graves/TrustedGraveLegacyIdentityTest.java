package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.CompletionException;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import com.bencodez.gravestonesplus.storage.GravesConfig;

class TrustedGraveLegacyIdentityTest {
    @Test void ownerDeathOverloadsWriteAndReadTheSameLocationBoundGrantsAsCommands() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var disk = new FeaturePersistenceTest.Disk();
            var store = new TrustedGraveAccessStore(new DurableFeatureState(h.io, disk, 4096), System::currentTimeMillis, h.plugin);
            assertTrue(FeaturePersistenceTest.await(store.loaded()));
            when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of());
            UUID owner = h.owner.getUniqueId(), helper = h.helper.getUniqueId(); long time = h.grave.getGravesConfig().getTime();
            assertTrue(FeaturePersistenceTest.await(store.grantAsync(owner, time, helper, TrustedGraveAccess.Kind.TRACK, System.currentTimeMillis() + 60_000)));
            assertTrue(store.allows(h.identity(), helper, TrustedGraveAccess.Kind.TRACK));
            assertFalse(store.allows(h.identity(), helper, TrustedGraveAccess.Kind.RECOVER));
            assertTrue(store.allows(owner, time, helper, TrustedGraveAccess.Kind.TRACK)); assertEquals(1, store.list(owner, time).size());
            assertEquals(Set.of(h.identity() + "/" + helper + "/TRACK"), disk.values.keySet());
            assertTrue(FeaturePersistenceTest.await(store.revokeAsync(owner, time, helper, TrustedGraveAccess.Kind.TRACK)));
            assertFalse(store.allows(h.identity(), helper, TrustedGraveAccess.Kind.TRACK));
            assertTrue(FeaturePersistenceTest.await(store.grantAsync(h.identity(), helper, TrustedGraveAccess.Kind.RECOVER, System.currentTimeMillis() + 60_000)));
            assertEquals(1, store.list(owner, time).size());
            FeaturePersistenceTest.await(store.revokeForGraveAsync(owner, time)); assertTrue(store.list(h.identity()).isEmpty());
            verify(h.world, never()).getChunkAt(anyInt(), anyInt());
        }
    }
    @Test void missingAndAmbiguousReferencesCannotGrantOrRevokeAnotherGrave() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var disk = new FeaturePersistenceTest.Disk();
            var store = new TrustedGraveAccessStore(new DurableFeatureState(h.io, disk, 4096), System::currentTimeMillis, h.plugin);
            FeaturePersistenceTest.await(store.loaded()); when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of());
            UUID owner = h.owner.getUniqueId(), helper = h.helper.getUniqueId(); long time = h.grave.getGravesConfig().getTime();
            assertFalse(FeaturePersistenceTest.await(store.grantAsync(null, time, helper, TrustedGraveAccess.Kind.TRACK, System.currentTimeMillis() + 60_000)));
            assertFalse(FeaturePersistenceTest.await(store.grantAsync(owner, time - 1, helper, TrustedGraveAccess.Kind.TRACK, System.currentTimeMillis() + 60_000)));
            assertTrue(FeaturePersistenceTest.await(store.grantAsync(h.identity(), helper, TrustedGraveAccess.Kind.TRACK, System.currentTimeMillis() + 60_000)));
            var other = new Grave(h.plugin, new GravesConfig(owner, "Owner", new Location(h.world, 5, 64, 1), new HashMap<>(), 0,
                    "other", time, true, 0, null, null, "PLAYER_HEAD"));
            when(h.plugin.brokenGraveSnapshot()).thenReturn(List.of(other));
            assertFalse(FeaturePersistenceTest.await(store.grantAsync(owner, time, helper, TrustedGraveAccess.Kind.RECOVER, System.currentTimeMillis() + 60_000)));
            assertFalse(FeaturePersistenceTest.await(store.revokeAsync(owner, time, helper, TrustedGraveAccess.Kind.TRACK)));
            assertFalse(store.allows(owner, time, helper, TrustedGraveAccess.Kind.TRACK)); assertTrue(store.list(owner, time).isEmpty());
            assertThrows(CompletionException.class, () -> store.revokeForGraveAsync(owner, time).join());
            assertTrue(store.allows(h.identity(), helper, TrustedGraveAccess.Kind.TRACK)); assertEquals(1, disk.values.size());
        }
    }
    @Test void standaloneRestartResolvesCanonicalRowsButNeverPromotesShortenedRows() throws Exception {
        try (var io = new FeatureIO()) {
            var disk = new FeaturePersistenceTest.Disk(); String id = FeaturePersistenceTest.GRAVE;
            String[] parts = id.split("/"); UUID owner = UUID.fromString(parts[0]), helper = FeaturePersistenceTest.HELPER; long time = Long.parseLong(parts[1]);
            var store = new TrustedGraveAccessStore(new DurableFeatureState(io, disk, 4096), () -> 100L);
            FeaturePersistenceTest.await(store.loaded());
            assertFalse(FeaturePersistenceTest.await(store.grantAsync(owner, time, helper, TrustedGraveAccess.Kind.TRACK, 200)));
            assertTrue(FeaturePersistenceTest.await(store.grantAsync(id, helper, TrustedGraveAccess.Kind.TRACK, 200)));
            var restarted = new TrustedGraveAccessStore(new DurableFeatureState(io, disk, 4096), () -> 100L);
            FeaturePersistenceTest.await(restarted.loaded()); assertTrue(restarted.allows(owner, time, helper, TrustedGraveAccess.Kind.TRACK));
            assertTrue(FeaturePersistenceTest.await(restarted.revokeAsync(owner, time, helper, TrustedGraveAccess.Kind.TRACK)));
            disk.values = Map.of(owner + "/" + time + "/" + helper + "/TRACK", "200");
            var legacy = new TrustedGraveAccessStore(new DurableFeatureState(io, disk, 4096), () -> 100L);
            FeaturePersistenceTest.await(legacy.loaded()); assertFalse(legacy.allows(owner, time, helper, TrustedGraveAccess.Kind.TRACK));
            assertFalse(FeaturePersistenceTest.await(legacy.grantAsync(owner, time, helper, TrustedGraveAccess.Kind.RECOVER, 200)));
            assertEquals(1, disk.values.size());
        }
    }
    @Test void wholeGraveCompatibilityRevocationReportsPersistenceFailure() throws Exception {
        try (var io = new FeatureIO()) {
            var disk = new FeaturePersistenceTest.Disk(); String id = FeaturePersistenceTest.GRAVE;
            var store = new TrustedGraveAccessStore(new DurableFeatureState(io, disk, 4096), () -> 100L);
            FeaturePersistenceTest.await(store.loaded());
            assertTrue(FeaturePersistenceTest.await(store.grantAsync(id, FeaturePersistenceTest.HELPER, TrustedGraveAccess.Kind.TRACK, 200)));
            disk.fail = true; String[] parts = id.split("/");
            assertThrows(CompletionException.class, () -> store.revokeForGraveAsync(UUID.fromString(parts[0]), Long.parseLong(parts[1])).join());
            assertFalse(store.allows(id, FeaturePersistenceTest.HELPER, TrustedGraveAccess.Kind.TRACK));
        }
    }
}
