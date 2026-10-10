package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

/** Regression coverage for legacy compasses during asynchronous restoration admission. */
class NavigationLegacyCompassRegressionTest {
    @Test void exactPendingLegacyCompassSurvivesAfterBackendLoadingCompletes() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.active.clear();
            long time = h.grave.getGravesConfig().getTime();
            var restoration = mock(GraveRestoration.class);
            when(restoration.isLoading()).thenReturn(false);
            when(restoration.hasPending(h.identity())).thenReturn(true);
            when(h.plugin.getGraveRestoration()).thenReturn(restoration);
            Compass compass = compass(h, time, new Location(h.world, 1, 64, 1));
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] { compass.item });

            h.navigation.refresh(h.owner);

            verify(compass.data, never()).remove(any());
            verify(compass.item, never()).setItemMeta(any());
        }
    }

    @Test void unrelatedLegacyCompassIsClearedEvenWhileAnotherRecordIsPending() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.active.clear();
            var restoration = mock(GraveRestoration.class);
            when(restoration.isLoading()).thenReturn(false);
            when(restoration.hasPending(h.identity())).thenReturn(false);
            when(h.plugin.getGraveRestoration()).thenReturn(restoration);
            Compass compass = compass(h, h.grave.getGravesConfig().getTime() + 1, new Location(h.world, 1, 64, 1));
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] { compass.item });

            h.navigation.refresh(h.owner);

            verify(compass.data, times(2)).remove(any());
            verify(compass.item).setItemMeta(compass.meta);
        }
    }

    @Test void legacyCompassIsClearedAfterItsPendingRecordResolvesWithoutAdmission() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.active.clear();
            long time = h.grave.getGravesConfig().getTime();
            var restoration = mock(GraveRestoration.class);
            when(restoration.isLoading()).thenReturn(false);
            when(restoration.hasPending(h.identity())).thenReturn(true, false);
            when(h.plugin.getGraveRestoration()).thenReturn(restoration);
            Compass compass = compass(h, time, new Location(h.world, 1, 64, 1));
            when(h.ownerInventory.getContents()).thenReturn(new ItemStack[] { compass.item });

            h.navigation.refresh(h.owner);
            verify(compass.data, never()).remove(any());
            reset(compass.data, compass.item);
            when(compass.item.getType()).thenReturn(Material.COMPASS);
            when(compass.item.getItemMeta()).thenReturn(compass.meta);
            when(compass.meta.getPersistentDataContainer()).thenReturn(compass.data);
            when(compass.data.get(any(), eq(PersistentDataType.STRING))).thenReturn(null);
            when(compass.data.has(any(), eq(PersistentDataType.LONG))).thenReturn(true);
            when(compass.data.get(any(), eq(PersistentDataType.LONG))).thenReturn(time);
            when(compass.meta.getLodestone()).thenReturn(new Location(h.world, 1, 64, 1));

            h.navigation.refresh(h.owner);

            verify(compass.data, times(2)).remove(any());
            verify(compass.item).setItemMeta(compass.meta);
        }
    }

    private static Compass compass(GraveActionsTest.Harness h, long time, Location location) {
        ItemStack item = mock(ItemStack.class);
        CompassMeta meta = mock(CompassMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(item.getType()).thenReturn(Material.COMPASS);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(), eq(PersistentDataType.STRING))).thenReturn(null);
        when(data.has(any(), eq(PersistentDataType.LONG))).thenReturn(true);
        when(data.get(any(), eq(PersistentDataType.LONG))).thenReturn(time);
        when(meta.getLodestone()).thenReturn(location);
        return new Compass(item, meta, data);
    }

    private record Compass(ItemStack item, CompassMeta meta, PersistentDataContainer data) { }
}
