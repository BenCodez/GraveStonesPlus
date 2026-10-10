package com.bencodez.gravestonesplus.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.FeatureIO;

class GraveStorageManagerTerminalCloseTest {
    @Test
    void failedWriteIsRetainedBeforeExactlyOneTerminalClose() throws Exception {
        var backend = mock(GraveStorageBackend.class);
        try (FeatureIO io = new FeatureIO(1)) {
            var plugin = mock(GraveStonesPlus.class);
            when(plugin.getFeatureIO()).thenReturn(io);
            when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            when(plugin.activeGraveSnapshot()).thenReturn(List.of());
            when(plugin.brokenGraveSnapshot()).thenReturn(List.of());
            doThrow(new RuntimeException("write failure")).when(backend).saveGraves(any());
            var manager = manager(plugin, backend);

            manager.saveGravesFromObjects(List.of());
            io.barrier().join();
            manager.close();
        }
        verify(backend, times(1)).close();
    }

    @Test
    void closeRequestSurvivesSaturatedQueueAndClosesOnceAfterRetainedDrain() throws Exception {
        var backend = mock(GraveStorageBackend.class);
        try (FeatureIO io = new FeatureIO(1)) {
            var plugin = mock(GraveStonesPlus.class);
            when(plugin.getFeatureIO()).thenReturn(io);
            when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
            when(plugin.activeGraveSnapshot()).thenReturn(List.of());
            when(plugin.brokenGraveSnapshot()).thenReturn(List.of());
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var first = io.submit(() -> {
                entered.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
                return null;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var second = io.submit(() -> null);
            var manager = manager(plugin, backend);
            manager.saveGravesFromObjects(List.of());
            assertNull(io.admit(), "running and queued work must occupy every admission");
            manager.close();
            manager.close();
            release.countDown();
            first.join();
            second.join();
            manager.saveGravesFromObjects(List.of());
        }
        verify(backend, times(1)).close();
        verify(backend, times(1)).saveGraves(any());
        var order = inOrder(backend);
        order.verify(backend).saveBrokenGraves(any());
        order.verify(backend).saveGraves(any());
        order.verify(backend).close();
    }

    @Test void idleAndRestoringManagersCloseWithoutWaitingForWorkerShutdown() throws Exception {
        for (boolean loading : List.of(false, true)) {
            var backend = mock(GraveStorageBackend.class);
            var closed = new CountDownLatch(1);
            doAnswer(call -> { assertEquals("gsp-feature-io", Thread.currentThread().getName()); closed.countDown(); return null; })
                    .when(backend).close();
            try (FeatureIO io = new FeatureIO(1)) {
                var plugin = plugin(io);
                var manager = manager(plugin, backend);
                if (loading) manager.beginRestoration();
                manager.close(); manager.close();
                assertTrue(closed.await(3, TimeUnit.SECONDS), "standalone close must wake the disk owner");
                manager.saveGravesFromObjects(List.of());
            }
            verify(backend, times(1)).close();
            verify(backend, never()).saveGraves(any());
        }
    }

    @Test void queuedFailureRecoversBeforeCloseAndDoesNotResurrectRecoveryAfterDrain() throws Exception {
        var backend = mock(GraveStorageBackend.class);
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var order = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            if (writes.incrementAndGet() == 1) {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                order.add("failed"); throw new IllegalStateException("fixture rejected write");
            }
            order.add("recovered"); return null;
        }).when(backend).saveGraves(any());
        doAnswer(call -> { order.add("closed"); return null; }).when(backend).close();
        GraveStorageManager manager;
        try (FeatureIO io = new FeatureIO(2)) {
            manager = manager(plugin(io), backend);
            manager.saveGravesFromObjects(List.of());
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                manager.close(); manager.close();
            } finally { release.countDown(); }
        }
        assertEquals(List.of("failed", "recovered", "closed"), order);
        var retained = GraveStorageManager.class.getDeclaredField("retained"); retained.setAccessible(true);
        assertNull(retained.get(manager), "a completed failed-write callback must not re-retain recovered state");
        manager.saveGravesFromObjects(List.of());
        assertEquals(2, writes.get()); verify(backend, times(1)).close();
    }

    @Test void permanentlyFailedRecoveryRetainsEvidenceAndNeverWritesAfterClose() throws Exception {
        var backend = mock(GraveStorageBackend.class);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> { assertFalse(closed.get(), "no retry may use a closed backend"); throw new IllegalStateException("fixture unavailable"); })
                .when(backend).saveGraves(any());
        doAnswer(call -> { assertTrue(closed.compareAndSet(false, true)); return null; }).when(backend).close();
        GraveStorageManager manager;
        try (FeatureIO io = new FeatureIO(2)) {
            manager = manager(plugin(io), backend);
            manager.saveGravesFromObjects(List.of());
            manager.close();
        }
        assertTrue(closed.get()); verify(backend, times(1)).close();
        var retained = GraveStorageManager.class.getDeclaredField("retained"); retained.setAccessible(true);
        assertNotNull(retained.get(manager), "failed recovery must remain observable rather than report a successful flush");
    }

    private static GraveStonesPlus plugin(FeatureIO io) {
        var plugin = mock(GraveStonesPlus.class);
        when(plugin.getFeatureIO()).thenReturn(io);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.activeGraveSnapshot()).thenReturn(List.of());
        when(plugin.brokenGraveSnapshot()).thenReturn(List.of());
        return plugin;
    }

    private static GraveStorageManager manager(GraveStonesPlus plugin, GraveStorageBackend backend) throws Exception {
        var manager = new GraveStorageManager(plugin);
        var field = GraveStorageManager.class.getDeclaredField("backend");
        field.setAccessible(true);
        field.set(manager, backend);
        return manager;
    }
}
