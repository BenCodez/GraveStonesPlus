package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

class GraveRegionOwnershipTest {
    static boolean owned(Location ignored) { return true; }
    static boolean remote(Location ignored) { return false; }
    static boolean unavailable(Location ignored) { throw new IllegalStateException("unavailable"); }

    @Test void nativeOwnershipMustBeTrueAndMissingOrFailedCapabilityRejects() throws Exception {
        Location location = new Location(null, 0, 0, 0);
        for (String method : List.of("owned", "remote", "unavailable")) {
            Method check = GraveRegionOwnershipTest.class.getDeclaredMethod(method, Location.class);
            assertEquals(method.equals("owned"), Grave.isOwnedByCurrentRegion(location, check));
        }
        assertFalse(Grave.isOwnedByCurrentRegion(location, null));
        assertFalse(Grave.isOwnedByCurrentRegion(null));
    }

    @Test void actorRecreationRequiresNativeLocationOwnershipOnItsFinalEntityCallback() throws Exception {
        for (boolean owned : List.of(false, true)) {
            try (var h = new GraveActionsTest.Harness(); var checks = mockStatic(Grave.class, CALLS_REAL_METHODS)) {
                when(h.folia.isFolia()).thenReturn(true);
                var actorOwner = new AtomicBoolean();
                when(h.helper.hasPermission(anyString())).thenAnswer(call -> {
                    assertTrue(actorOwner.get(), "authorization must use the actor owner");
                    return call.getArgument(0).equals("GraveStonesPlus.Admin");
                });
                var grave = spy(h.grave);
                doAnswer(call -> { assertTrue(actorOwner.get()); assertTrue(owned); return null; }).when(grave).createGrave();
                doNothing().when(grave).createHologram();
                h.active.clear(); grave.getGravesConfig().setDestroyed(true);
                checks.when(() -> Grave.isOwnedByCurrentRegion(grave.getGravesConfig().getLocation())).thenAnswer(call -> {
                    assertTrue(actorOwner.get(), "location proof must use the current actor region"); return owned;
                });
                var restored = grave.recreateAsync(h.helper, true); h.drain();
                assertEquals(1, h.regionWork.size()); assertFalse(restored.isDone());
                h.regionWork.remove().run();
                assertEquals(1, h.entityWork.size()); assertTrue(grave.getGravesConfig().isDestroyed());
                verify(grave, never()).createGrave();
                actorOwner.set(true); h.entityWork.remove().run(); actorOwner.set(false);
                assertEquals(owned, FeaturePersistenceTest.await(restored));
                assertEquals(!owned, grave.getGravesConfig().isDestroyed());
                verify(grave, times(owned ? 1 : 0)).markRecreated();
                verify(grave, times(owned ? 1 : 0)).createGrave();
            }
        }
    }

    @Test void locationOnlyRecreationAndNonFoliaActorKeepTheirEstablishedOwnerPaths() throws Exception {
        for (boolean locationOnly : List.of(false, true)) {
            try (var h = new GraveActionsTest.Harness(); var checks = mockStatic(Grave.class, CALLS_REAL_METHODS)) {
                when(h.folia.isFolia()).thenReturn(locationOnly);
                when(h.helper.hasPermission("GraveStonesPlus.Admin")).thenReturn(true);
                var grave = spy(h.grave); doNothing().when(grave).createGrave(); doNothing().when(grave).createHologram();
                grave.getGravesConfig().setDestroyed(true);
                var restored = grave.recreateAsync(locationOnly ? null : h.helper, true); h.drain();
                h.regionWork.remove().run();
                if (!locationOnly) h.entityWork.remove().run();
                assertTrue(FeaturePersistenceTest.await(restored)); verify(grave).createGrave();
                checks.verify(() -> Grave.isOwnedByCurrentRegion(grave.getGravesConfig().getLocation()), never());
            }
        }
    }
}
