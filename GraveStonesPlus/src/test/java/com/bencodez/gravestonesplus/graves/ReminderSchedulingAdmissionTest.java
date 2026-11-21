package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import com.bencodez.gravestonesplus.listeners.GraveReminderListener;
import com.bencodez.simpleapi.folialib.enums.EntityTaskResult;
import com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask;

class ReminderSchedulingAdmissionTest {
    @Test void rejectedEntitySchedulingDoesNotPoisonLaterJoins() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = spy(new GraveReminderListener(h.plugin));
            doNothing().when(listener).checkPlayer(any()); listener.start();
            doThrow(new RejectedExecutionException("fixture retired entity"))
                .when(h.scheduler).runTask(eq(h.plugin), any(Runnable.class), any(Entity.class));
            doThrow(new RejectedExecutionException("fixture retired entity"))
                .when(h.platform).runAtEntityWithFallback(any(Entity.class), any(), any(Runnable.class));
            assertDoesNotThrow(() -> listener.onJoin(new PlayerJoinEvent(h.owner, "")));
            var field = GraveReminderListener.class.getDeclaredField("scheduled"); field.setAccessible(true);
            assertTrue(((Map<?, ?>) field.get(listener)).isEmpty());
            listener.stop();
        }
    }
    @Test void retiredAndFailedEntityTasksReleaseTheirExactAdmission() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = spy(new GraveReminderListener(h.plugin));
            doNothing().when(listener).checkPlayer(any()); listener.start();
            var retired = new ArrayList<Runnable>(); var futures = new ArrayList<CompletableFuture<EntityTaskResult>>();
            doAnswer(call -> { retired.add(call.getArgument(2)); var future = new CompletableFuture<EntityTaskResult>();
                futures.add(future); return future;
            }).when(h.platform).runAtEntityWithFallback(any(Entity.class), any(), any(Runnable.class));
            listener.onJoin(new PlayerJoinEvent(h.owner, "")); assertEquals(1, retired.size());
            retired.get(0).run(); listener.onJoin(new PlayerJoinEvent(h.owner, "")); assertEquals(2, retired.size());
            futures.get(1).completeExceptionally(new RejectedExecutionException("fixture scheduler failed"));
            listener.onJoin(new PlayerJoinEvent(h.owner, "")); assertEquals(3, retired.size());
            retired.get(2).run(); listener.stop(); verify(listener, never()).checkPlayer(any());
        }
    }
    @Test void staleCallbackAfterQuitAndReloadCannotClearOrRunTheSuccessor() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var listener = spy(new GraveReminderListener(h.plugin));
            doNothing().when(listener).checkPlayer(any()); listener.start();
            var work = new ArrayList<Consumer<WrappedTask>>(); var retired = new ArrayList<Runnable>();
            doAnswer(call -> { work.add(call.getArgument(1)); retired.add(call.getArgument(2));
                return new CompletableFuture<EntityTaskResult>();
            }).when(h.platform).runAtEntityWithFallback(any(Entity.class), any(), any(Runnable.class));
            listener.onJoin(new PlayerJoinEvent(h.owner, ""));
            listener.onQuit(new PlayerQuitEvent(h.owner, ""));
            listener.onJoin(new PlayerJoinEvent(h.owner, "")); assertEquals(2, work.size());
            work.get(0).accept(null); retired.get(0).run();
            listener.onJoin(new PlayerJoinEvent(h.owner, "")); assertEquals(2, work.size());
            listener.stop(); listener.start(); listener.onJoin(new PlayerJoinEvent(h.owner, "")); assertEquals(3, work.size());
            work.get(1).accept(null); retired.get(1).run();
            listener.onJoin(new PlayerJoinEvent(h.owner, "")); assertEquals(3, work.size());
            work.get(2).accept(null); verify(listener, times(1)).checkPlayer(h.owner);
            listener.onJoin(new PlayerJoinEvent(h.owner, "")); assertEquals(4, work.size());
            retired.get(3).run(); listener.stop();
        }
    }
}
