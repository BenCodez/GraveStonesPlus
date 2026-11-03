package com.bencodez.gravestonesplus.graves;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.bencodez.gravestonesplus.listeners.GraveReminderListener;

/** Regression coverage for login summaries racing asynchronous startup admission. */
class GraveLoginReadinessTest {
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
