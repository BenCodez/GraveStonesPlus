package com.bencodez.gravestonesplus.tests.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.config.Config;
import com.bencodez.gravestonesplus.graves.DurableFeatureState;
import com.bencodez.gravestonesplus.graves.FeatureIO;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GraveClaimLedger;
import com.bencodez.gravestonesplus.graves.GraveDeadlines;
import com.bencodez.gravestonesplus.graves.GraveReminderStore;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.bencodez.gravestonesplus.listeners.GraveReminderListener;
import com.bencodez.simpleapi.time.ParsedDuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class GraveReminderKindsTest {
    @TempDir Path directory;

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(3, TimeUnit.SECONDS);
    }

    /** Reuses the existing fixture without changing its visibility or production APIs. */
    private static final class Harness implements AutoCloseable {
        private final AutoCloseable delegate;
        final FeatureIO io;
        final GraveStonesPlus plugin;
        final Config config;
        final Grave grave;
        final GraveClaimLedger ledger;
        final Player owner;
        final PlayerInventory ownerInventory;
        final List<Grave> active;
        final Queue<Runnable> entityWork, regionWork, ownerWork;

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
                plugin = field("plugin", GraveStonesPlus.class);
                config = field("config", Config.class);
                grave = field("grave", Grave.class);
                ledger = field("ledger", GraveClaimLedger.class);
                owner = field("owner", Player.class);
                ownerInventory = field("ownerInventory", PlayerInventory.class);
                active = graves();
                entityWork = queue("entityWork");
                regionWork = queue("regionWork");
                ownerWork = queue("ownerWork");
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
        @SuppressWarnings("unchecked")
        private Queue<Runnable> queue(String name) throws Exception { return (Queue<Runnable>) field(name, Queue.class); }
        String identity() throws Exception {
            var method = delegate.getClass().getDeclaredMethod("identity");
            method.setAccessible(true);
            return (String) method.invoke(delegate);
        }
        @Override public void close() throws Exception { delegate.close(); }
    }


    private static long configureCoincidentDeadlines(Harness h) throws Exception {
        long duration = TimeUnit.MINUTES.toMillis(30);
        long deadline = GraveDeadlines.deadline(h.grave.getGravesConfig().getTime(), duration);
        Field expiry = Grave.class.getDeclaredField("expiryDeadline");
        expiry.setAccessible(true);
        expiry.setLong(h.grave, deadline);
        when(h.config.isBreakOtherGravesEnabled()).thenReturn(true);
        when(h.config.getBreakOtherGravesTimeBeforeBreakable())
                .thenReturn(ParsedDuration.parse("30m", TimeUnit.MINUTES));
        when(h.config.getExpiryReminderMillis()).thenReturn(TimeUnit.HOURS.toMillis(1));
        when(h.config.getProtectionReminderMillis()).thenReturn(TimeUnit.HOURS.toMillis(1));
        return deadline;
    }

    private static GraveReminderStore load(Harness h, Path file) throws Exception {
        var store = new GraveReminderStore(new DurableFeatureState(h.io, file, 8192));
        assertTrue(await(store.loaded()));
        when(h.plugin.getGraveReminderStore()).thenReturn(store);
        return store;
    }

    private static void assertNoClaim(Harness h) throws Exception {
        assertTrue(h.entityWork.isEmpty(), "reminders must not queue entity gameplay work");
        assertTrue(h.regionWork.isEmpty(), "reminders must not queue region gameplay work");
        assertTrue(h.ownerWork.isEmpty(), "reminders must not queue global gameplay work");
        verify(h.owner, never()).giveExp(anyInt());
        verify(h.ownerInventory, never()).setItem(anyInt(), any());
        verify(h.plugin, never()).removeGrave(any());
        assertFalse(h.ledger.isBlocked(h.identity()));
        assertFalse(h.grave.getGravesConfig().isDestroyed());
        assertTrue(h.active.contains(h.grave));
    }

    @Test void sameDeadlineKindsShareOneSummaryAndRemainSuppressedAfterStoreReload() throws Exception {
        Path file = directory.resolve("reminders.yml");
        try (var h = new Harness()) {
            long deadline = configureCoincidentDeadlines(h);
            load(h, file);
            var listener = new GraveReminderListener(h.plugin);
            listener.start();
            try {
                listener.checkPlayer(h.owner);
                await(h.io.barrier());
                listener.checkPlayer(h.owner);
                var message = ArgumentCaptor.forClass(String.class);
                verify(h.owner, times(1)).sendMessage(message.capture());
                assertTrue(message.getValue().contains(" expires in "));
                assertTrue(message.getValue().contains(" protection ends in "));
                assertNoClaim(h);

                listener.stop();
                var restoredState = new DurableFeatureState(h.io, file, 8192);
                assertTrue(await(restoredState.loaded()));
                String prefix = h.owner.getUniqueId() + "/" + h.identity();
                assertEquals(Set.of(prefix + "/expiry/" + deadline, prefix + "/protection/" + deadline),
                        restoredState.snapshot().keySet());
                when(h.plugin.getGraveReminderStore()).thenReturn(new GraveReminderStore(restoredState));
                var reloaded = new GraveReminderListener(h.plugin);
                reloaded.start();
                try {
                    clearInvocations(h.owner);
                    reloaded.checkPlayer(h.owner);
                    reloaded.checkPlayer(h.owner);
                    verify(h.owner, never()).sendMessage(anyString());
                } finally { reloaded.stop(); }
                assertNoClaim(h);
            } finally { listener.stop(); }
        }
    }

    @Test void enablingTheOtherThresholdDoesNotRepeatTheAlreadyDeliveredKind() throws Exception {
        try (var h = new Harness()) {
            configureCoincidentDeadlines(h);
            when(h.config.getProtectionReminderMillis()).thenReturn(0L);
            load(h, directory.resolve("thresholds.yml"));
            var listener = new GraveReminderListener(h.plugin);
            listener.start();
            try {
                listener.checkPlayer(h.owner);
                await(h.io.barrier());
                var message = ArgumentCaptor.forClass(String.class);
                verify(h.owner).sendMessage(message.capture());
                assertTrue(message.getValue().contains(" expires in "));
                assertFalse(message.getValue().contains(" protection ends in "));
                assertNoClaim(h);

                when(h.config.getProtectionReminderMillis()).thenReturn(TimeUnit.HOURS.toMillis(1));
                clearInvocations(h.owner);
                listener.checkPlayer(h.owner);
                await(h.io.barrier());
                listener.checkPlayer(h.owner);
                message = ArgumentCaptor.forClass(String.class);
                verify(h.owner, times(1)).sendMessage(message.capture());
                assertTrue(message.getValue().contains(" protection ends in "));
                assertFalse(message.getValue().contains(" expires in "));
                assertNoClaim(h);
            } finally { listener.stop(); }
        }
    }
}
