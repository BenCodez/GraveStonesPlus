package com.bencodez.gravestonesplus.graves;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.bencodez.gravestonesplus.listeners.GraveReminderListener;

/** Regression coverage for login summaries racing asynchronous startup admission. */
class GraveLoginReadinessTest {
    @Test void loginSummaryWaitsForInitialBatchesButNotUnavailableWorldsOrChunks() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            h.active.clear();
            when(h.config.getGraveTimeLimit()).thenReturn("0");
            var records = new java.util.ArrayList<com.bencodez.gravestonesplus.storage.GravesConfig>();
            for (int i = 0; i < 129; i++) records.add(new com.bencodez.gravestonesplus.storage.GravesConfig(
                    h.owner.getUniqueId(), "Owner", new org.bukkit.Location(h.world, i, 64, 1),
                    new java.util.HashMap<>(), 0, "", 1000L + i, false, 0, null, null, "PLAYER_HEAD"));
            var missing = new com.bencodez.gravestonesplus.storage.GravesConfig(
                    h.owner.getUniqueId(), "Owner", new org.bukkit.Location(null, 1, 64, 1),
                    new java.util.HashMap<>(), 0, "", 2000L, false, 0, null, null, "PLAYER_HEAD");
            missing.setWorldId(java.util.UUID.randomUUID()); records.add(missing);
            var storage = mock(com.bencodez.gravestonesplus.storage.GraveStorageManager.class);
            when(storage.loadGraves()).thenReturn(records); when(storage.loadBrokenGraves()).thenReturn(java.util.List.of());
            when(h.plugin.getFeatureIO()).thenReturn(h.io);
            doAnswer(call -> { h.active.add(call.getArgument(0)); return null; }).when(h.plugin).restoreActiveRecord(any());
            var restoration = new GraveRestoration(h.plugin, storage);
            when(h.plugin.getGraveRestoration()).thenReturn(restoration);
            var reminders = new GraveReminderStore(new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192));
            FeaturePersistenceTest.await(reminders.loaded()); when(h.plugin.getGraveReminderStore()).thenReturn(reminders);
            when(h.platform.runTimer(any(Runnable.class), eq(20L), eq(100L)))
                    .thenReturn(mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class));
            var listener = new GraveReminderListener(h.plugin); listener.start();
            try {
                GraveActionsTest.retryOnOwner(h, restoration);
                for (int i = 0; i < 128; i++) {
                    h.drain(); org.junit.jupiter.api.Assertions.assertFalse(h.regionWork.isEmpty());
                    h.regionWork.remove().run();
                }
                h.drain(); org.junit.jupiter.api.Assertions.assertEquals(128, h.active.size());
                listener.onJoin(new org.bukkit.event.player.PlayerJoinEvent(h.owner, "")); h.drain();
                verify(h.owner, never()).sendMessage(anyString());
                GraveActionsTest.retryOnOwner(h, restoration);
                for (int i = 0; i < 130; i++) {
                    h.drain(); Runnable region = h.regionWork.poll(); if (region != null) region.run();
                }
                listener.checkPlayer(h.owner); listener.checkPlayer(h.owner);
                verify(h.owner, times(1)).sendMessage(argThat((String message) -> message.contains("129 accessible grave")));
                org.junit.jupiter.api.Assertions.assertTrue(restoration.hasPending(GraveIdentity.of(missing)));
            } finally { listener.stop(); restoration.retire(); }
        }
    }
    @Test void loginSummaryWaitsForFeatureStoresAndRestoration() throws Exception {
        try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            var reminders = new GraveReminderStore(new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192));
            FeaturePersistenceTest.await(reminders.loaded());
            when(h.plugin.getGraveReminderStore()).thenReturn(reminders);
            var restoration = mock(GraveRestoration.class);
            when(h.plugin.getGraveRestoration()).thenReturn(restoration);
            when(restoration.isLoading()).thenReturn(true);
            h.bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(h.owner));
            AtomicReference<Runnable> tick = new AtomicReference<>();
            when(h.platform.runTimer(any(Runnable.class), eq(20L), eq(100L)))
                    .thenAnswer(invocation -> { tick.set(invocation.getArgument(0)); return mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class); });
            var listener = new GraveReminderListener(h.plugin);
            listener.start();
            listener.onJoin(new org.bukkit.event.player.PlayerJoinEvent(h.owner, ""));
            h.drain();
            verify(h.owner, never()).sendMessage(anyString());

            when(restoration.isLoading()).thenReturn(false);
            tick.get().run();
            h.drain();
            verify(h.owner).sendMessage(argThat((String message) -> message.contains("accessible grave")));
            listener.stop();
        }
    }

    @Test void failedReadinessRetriesAndQuitOrStopDropsDeferredLogin() throws Exception {
        try (GraveActionsTest.Harness h = new GraveActionsTest.Harness()) {
            var reminders = new GraveReminderStore(new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192));
            FeaturePersistenceTest.await(reminders.loaded());
            when(h.plugin.getGraveReminderStore()).thenReturn(reminders);
            var restoration = mock(GraveRestoration.class);
            when(h.plugin.getGraveRestoration()).thenReturn(restoration);
            when(restoration.isLoading()).thenReturn(true);
            h.bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(h.owner));
            var tick = new AtomicReference<Runnable>();
            when(h.platform.runTimer(any(Runnable.class), eq(20L), eq(100L)))
                    .thenAnswer(invocation -> { tick.set(invocation.getArgument(0)); return mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class); });
            var listener = new GraveReminderListener(h.plugin); listener.start();
            listener.onJoin(new org.bukkit.event.player.PlayerJoinEvent(h.owner, "")); h.drain();
            when(restoration.isLoading()).thenReturn(false);
            tick.get().run(); h.drain();
            verify(h.owner).sendMessage(argThat((String message) -> message.contains("accessible grave")));
            clearInvocations(h.owner);
            listener.onJoin(new org.bukkit.event.player.PlayerJoinEvent(h.owner, ""));
            listener.onQuit(new org.bukkit.event.player.PlayerQuitEvent(h.owner, ""));
            tick.get().run(); h.drain();
            verify(h.owner, never()).sendMessage(anyString());
            listener.stop();
        }
    }
    @Test void unavailableReminderStateAndFailedSendRetainOneLoginSummaryUntilSuccess() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var reminders = mock(GraveReminderStore.class); when(h.plugin.getGraveReminderStore()).thenReturn(reminders);
            var tick = new AtomicReference<Runnable>(); h.bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(h.owner));
            when(h.platform.runTimer(any(Runnable.class), eq(20L), eq(100L)))
                .thenAnswer(call -> { tick.set(call.getArgument(0)); return mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class); });
            var listener = new GraveReminderListener(h.plugin); listener.start();
            listener.onJoin(new org.bukkit.event.player.PlayerJoinEvent(h.owner, "")); h.drain();
            verify(h.owner, never()).sendMessage(anyString()); verify(reminders, never()).deliver(any(), anyLong(), any());
            when(reminders.isReady()).thenReturn(true);
            doThrow(new IllegalStateException("fixture send failure")).doNothing().when(h.owner).sendMessage(anyString());
            tick.get().run(); org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, h::drain);
            tick.get().run(); h.drain();
            tick.get().run(); h.drain();
            verify(h.owner, times(2)).sendMessage(argThat((String message) -> message.contains("accessible grave")));
            listener.stop();
        }
    }
    @Test void stoppedListenerCannotDeliverQueuedOrPeriodicLoginSummary() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var reminders = mock(GraveReminderStore.class); when(reminders.isReady()).thenReturn(true);
            when(h.plugin.getGraveReminderStore()).thenReturn(reminders);
            var tick = new AtomicReference<Runnable>(); h.bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(java.util.List.of(h.owner));
            when(h.platform.runTimer(any(Runnable.class), eq(20L), eq(100L)))
                .thenAnswer(call -> { tick.set(call.getArgument(0)); return mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class); });
            var listener = new GraveReminderListener(h.plugin); listener.start();
            listener.onJoin(new org.bukkit.event.player.PlayerJoinEvent(h.owner, "")); listener.stop();
            h.drain(); tick.get().run(); h.drain();
            verify(h.owner, never()).sendMessage(anyString()); verify(reminders, never()).deliver(any(), anyLong(), any());
        }
    }

}
