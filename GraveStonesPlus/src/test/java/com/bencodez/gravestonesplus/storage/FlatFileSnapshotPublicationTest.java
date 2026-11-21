package com.bencodez.gravestonesplus.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.FeatureIO;

class FlatFileSnapshotPublicationTest {
    @Test void flatFileSnapshotUpdatesBothSectionsBeforeOneSave() {
        GraveLocations locations = mock(GraveLocations.class);
        FlatFileGraveStorageBackend backend = new FlatFileGraveStorageBackend(locations);
        List<GravesConfig> active = List.of(), broken = List.of();
        backend.saveSnapshot(active, broken);
        verify(locations).saveSnapshot(active, broken);
        verifyNoMoreInteractions(locations);
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;

    @Test void graveLocationsPublishesBothSectionsAndRetainsLegacyShape() throws Exception {
        GraveLocations locations = mock(GraveLocations.class);
        var data = new org.bukkit.configuration.file.YamlConfiguration();
        java.nio.file.Path target = temporary.resolve("Graves.yml");
        java.nio.file.Files.writeString(target, "graves: []\nbrokengraves: [old]\n");
        when(locations.getData()).thenReturn(data);
        when(locations.getdFile()).thenReturn(target.toFile());
        doCallRealMethod().when(locations).saveSnapshot(any(), any());
        locations.saveSnapshot(List.of(), List.of());
        var stored = new org.bukkit.configuration.file.YamlConfiguration();
        stored.load(target.toFile());
        assertEquals(List.of(), stored.getList("graves"));
        assertEquals(List.of(), stored.getList("brokengraves"));
        verify(locations, never()).saveData();
        verify(locations, never()).setGrave(any());
        verify(locations, never()).setBrokenGrave(any());
        try (var files = java.nio.file.Files.list(temporary)) {
            assertEquals(List.of(target), files.toList());
        }
    }

    @Test void graveLocationsReportsPublicationFailureInsteadOfAcknowledgingIt() throws Exception {
        GraveLocations locations = mock(GraveLocations.class);
        java.nio.file.Path parentFile = temporary.resolve("not-a-directory");
        java.nio.file.Files.writeString(parentFile, "unchanged");
        when(locations.getData()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        when(locations.getdFile()).thenReturn(parentFile.resolve("Graves.yml").toFile());
        doCallRealMethod().when(locations).saveSnapshot(any(), any());
        assertThrows(java.io.UncheckedIOException.class, () -> locations.saveSnapshot(List.of(), List.of()));
        assertEquals("unchanged", java.nio.file.Files.readString(parentFile));
    }

    @Test void managerFutureWaitsForWorkerAndRetainsFailedPublication() throws Exception {
        GraveStonesPlus plugin = mock(GraveStonesPlus.class);
        try (FeatureIO io = new FeatureIO(4)) {
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("GraveSnapshotTest"));
        when(plugin.getFeatureIO()).thenReturn(io);
        when(plugin.activeGraveSnapshot()).thenReturn(List.of());
        when(plugin.brokenGraveSnapshot()).thenReturn(List.of());
        GraveStorageBackend backend = mock(GraveStorageBackend.class);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(invocation -> { started.countDown(); release.await(2, TimeUnit.SECONDS); return null; })
                .when(backend).saveSnapshot(any(), any());
        GraveStorageManager manager = new GraveStorageManager(plugin);
        var backendField = GraveStorageManager.class.getDeclaredField("backend");
        backendField.setAccessible(true); backendField.set(manager, backend);
        var pending = manager.saveSnapshotFromObjects(List.of(), List.of());
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertFalse(pending.isDone());
        release.countDown();
        assertTrue(pending.get(2, TimeUnit.SECONDS));
        doThrow(new IllegalStateException("disk")).when(backend).saveSnapshot(any(), any());
        var failed = manager.saveSnapshotFromObjects(List.of(), List.of());
        assertThrows(Exception.class, () -> failed.get(2, TimeUnit.SECONDS));
        var retained = GraveStorageManager.class.getDeclaredField("retained");
        retained.setAccessible(true);
        assertNotNull(retained.get(manager));
        }
    }
}
