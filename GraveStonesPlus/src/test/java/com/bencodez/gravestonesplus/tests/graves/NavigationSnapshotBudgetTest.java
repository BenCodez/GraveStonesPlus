package com.bencodez.gravestonesplus.tests.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.FeatureIO;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GraveNavigation;
import com.bencodez.gravestonesplus.graves.TrustedGraveAccess;
import com.bencodez.gravestonesplus.graves.TrustedGraveAccessStore;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NavigationSnapshotBudgetTest {
    private record Compass(ItemStack item, CompassMeta meta) { }

    /** Reuses the existing fixture without widening production or fixture visibility. */
    private static final class Harness implements AutoCloseable {
        private final AutoCloseable delegate;
        final FeatureIO io;
        final TrustedGraveAccessStore access;
        final GraveStonesPlus plugin;
        final World world;
        final Player owner, helper;
        final PlayerInventory ownerInventory, helperInventory;
        final List<Grave> active;
        final GraveNavigation navigation;
        final Grave grave;

        Harness() throws Exception {
            Class<?> type;
            try {
                type = Class.forName("com.bencodez.gravestonesplus.tests.graves.GraveActionsTest$Harness");
            } catch (ClassNotFoundException absentMovedFixture) {
                type = Class.forName("com.bencodez.gravestonesplus.graves.GraveActionsTest$Harness");
            }
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            delegate = (AutoCloseable) constructor.newInstance();
            try {
                io = field("io", FeatureIO.class);
                access = field("access", TrustedGraveAccessStore.class);
                plugin = field("plugin", GraveStonesPlus.class);
                world = field("world", World.class);
                owner = field("owner", Player.class);
                helper = field("helper", Player.class);
                ownerInventory = field("ownerInventory", PlayerInventory.class);
                helperInventory = field("helperInventory", PlayerInventory.class);
                active = graves();
                navigation = field("navigation", GraveNavigation.class);
                grave = field("grave", Grave.class);
            } catch (Exception failure) {
                try { delegate.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        private <T> T field(String name, Class<T> type) throws Exception {
            var field = delegate.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(delegate));
        }

        @SuppressWarnings("unchecked")
        private List<Grave> graves() throws Exception { return (List<Grave>) field("active", List.class); }

        String identity() throws Exception {
            var method = delegate.getClass().getDeclaredMethod("identity");
            method.setAccessible(true);
            return (String) method.invoke(delegate);
        }

        void grant(TrustedGraveAccess.Kind kind) throws Exception {
            assertTrue(access.grantAsync(identity(), helper.getUniqueId(), kind,
                    System.currentTimeMillis() + 60_000).get(3, TimeUnit.SECONDS));
        }

        @Override public void close() throws Exception { delegate.close(); }
    }

    private static Compass compass(Harness h, boolean legacy) throws Exception {
        String identity = h.identity();
        long time = h.grave.getGravesConfig().getTime();
        Location location = h.grave.getGravesConfig().getLocation().clone();
        ItemStack item = mock(ItemStack.class);
        CompassMeta meta = mock(CompassMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.COMPASS);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(), eq(PersistentDataType.STRING))).thenReturn(legacy ? null : identity);
        when(data.has(any(), eq(PersistentDataType.LONG))).thenReturn(legacy);
        when(data.get(any(), eq(PersistentDataType.LONG))).thenReturn(time);
        when(meta.getLodestone()).thenReturn(location);
        return new Compass(item, meta);
    }

    private static void noWorldLoading(Harness h) {
        verify(h.world, never()).getBlockAt(anyInt(), anyInt(), anyInt());
        verify(h.world, never()).getChunkAt(anyInt(), anyInt());
        verify(h.owner, never()).giveExp(anyInt());
        verify(h.helper, never()).giveExp(anyInt());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void repeatedCompassesToOneLiveTargetDoNotCopyTheWholeListPerSlot(boolean legacy) throws Exception {
        try (var h = new Harness()) {
            assertTrue(h.navigation.select(h.owner, h.grave));
            var compasses = new ArrayList<Compass>();
            for (int i = 0; i < 41; i++) compasses.add(compass(h, legacy));
            when(h.ownerInventory.getContents()).thenReturn(compasses.stream().map(Compass::item).toArray(ItemStack[]::new));
            clearInvocations(h.plugin);

            h.navigation.refresh(h.owner);

            for (Compass compass : compasses) {
                verify(compass.meta).setLodestone(h.grave.getGravesConfig().getLocation());
                verify(compass.item).setItemMeta(compass.meta);
            }
            verify(h.plugin, atMost(1)).activeGraveSnapshot();
            noWorldLoading(h);
        }
    }

    @Test void removalBetweenCompassesInvalidatesTheLaterCompass() throws Exception {
        try (var h = new Harness()) {
            Compass first = compass(h, false), second = compass(h, false);
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] {first.item, second.item});
            doAnswer(inv -> { h.active.remove(h.grave); return true; }).when(first.item).setItemMeta(first.meta);

            h.navigation.refresh(h.owner);

            verify(second.meta).setLodestone(null);
            verify(second.meta).setDisplayName("Inactive grave compass");
            noWorldLoading(h);
        }
    }

    @Test void replacementWithTheSameIdentityIsResolvedAfterTheOldObjectRetires() throws Exception {
        try (var h = new Harness()) {
            Compass first = compass(h, false), second = compass(h, false);
            Grave replacement = new Grave(h.plugin, h.grave.getGravesConfig());
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] {first.item, second.item});
            doAnswer(inv -> { h.active.remove(h.grave); h.active.add(replacement); return true; })
                    .when(first.item).setItemMeta(first.meta);

            h.navigation.refresh(h.owner);

            verify(second.meta).setLodestone(replacement.getGravesConfig().getLocation());
            verify(second.meta, never()).setDisplayName("Inactive grave compass");
            noWorldLoading(h);
        }
    }

    @Test void aMissDoesNotHideAdmissionBeforeTheNextCompassIsChecked() throws Exception {
        try (var h = new Harness()) {
            Compass first = compass(h, false), second = compass(h, false);
            h.active.clear();
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] {first.item, second.item});
            doAnswer(inv -> { h.active.add(h.grave); return true; }).when(first.item).setItemMeta(first.meta);

            h.navigation.refresh(h.owner);

            verify(first.meta).setLodestone(null);
            verify(second.meta).setLodestone(h.grave.getGravesConfig().getLocation());
            verify(second.meta, never()).setDisplayName("Inactive grave compass");
            noWorldLoading(h);
        }
    }

    @Test void revocationBetweenCompassesIsNotHiddenByTargetReuse() throws Exception {
        try (var h = new Harness()) {
            h.grant(TrustedGraveAccess.Kind.TRACK);
            Compass first = compass(h, false), second = compass(h, false);
            when(h.helperInventory.getContents()).thenReturn(new ItemStack[] {first.item, second.item});
            doAnswer(inv -> {
                h.access.revokeAsync(h.identity(), h.helper.getUniqueId(), TrustedGraveAccess.Kind.TRACK);
                return true;
            }).when(first.item).setItemMeta(first.meta);

            h.navigation.refresh(h.helper);
            h.io.barrier().get(3, TimeUnit.SECONDS);

            verify(first.meta).setLodestone(h.grave.getGravesConfig().getLocation());
            verify(second.meta).setLodestone(null);
            noWorldLoading(h);
        }
    }

    @Test void identityChangesCannotRetargetAnOldTaggedCompass() throws Exception {
        try (var h = new Harness()) {
            Compass first = compass(h, false), second = compass(h, false);
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] {first.item, second.item});
            doAnswer(inv -> { h.grave.getGravesConfig().getLocation().setX(2); return true; })
                    .when(first.item).setItemMeta(first.meta);

            h.navigation.refresh(h.owner);

            verify(second.meta).setLodestone(null);
            noWorldLoading(h);
        }
    }

    @Test void legacyCompassesStillRequireTheExactStoredLocation() throws Exception {
        try (var h = new Harness()) {
            assertTrue(h.navigation.select(h.owner, h.grave));
            Compass compass = compass(h, true);
            Location differentPositionInSameBlock = h.grave.getGravesConfig().getLocation().clone().add(.25, 0, 0);
            when(compass.meta.getLodestone()).thenReturn(differentPositionInSameBlock);
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] {compass.item});

            h.navigation.refresh(h.owner);

            verify(compass.meta).setLodestone(null);
            noWorldLoading(h);
        }
    }
}
