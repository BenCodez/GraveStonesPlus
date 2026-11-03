package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.gravestonesplus.listeners.GraveReminderListener;

class UnavailableWorldRenderingTest {
    @Test void allMenuAndCommandDisplaysRetainUnavailableWorldIdentityAndCoordinates() throws Exception {
        try (var h = new GraveActionsTest.Harness();
                var builders = mockConstruction(ItemBuilder.class, withSettings().defaultAnswer(RETURNS_SELF))) {
            var worldId = h.grave.getGravesConfig().getWorldId();
            h.grave.getGravesConfig().getLocation().setWorld(null);
            String position = "world unavailable [" + worldId + "] (1,64,1)";
            assertEquals(position, h.grave.getLocationDescription());
            assertTrue(h.grave.getGraveMessage().contains(position));
            assertTrue(h.grave.getAllGraveMessage().contains(position));
            assertEquals("world unavailable", h.grave.getNavigationMessage(h.owner));
            assertDoesNotThrow(h.grave::getGUIItem);
            assertDoesNotThrow(() -> h.grave.getNavigationGUIItem(h.owner));
            assertDoesNotThrow(h.grave::getGUIItemBroken);
            assertEquals(3, builders.constructed().size());
            for (var builder : builders.constructed()) verify(builder).addLoreLine("&3Location: " + position);
            verify(h.world, never()).getChunkAt(anyInt(), anyInt());
        }
    }

    @Test void loadedWorldAndMissingLocationHaveExplicitDisplayResults() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            assertEquals("overworld (1,64,1)", h.grave.getLocationDescription());
            assertTrue(h.grave.getGraveMessage().startsWith("Location: overworld (1,64,1) Time of death: "));
            var field = com.bencodez.gravestonesplus.storage.GravesConfig.class.getDeclaredField("location");
            field.setAccessible(true); field.set(h.grave.getGravesConfig(), null);
            assertEquals("location unavailable", h.grave.getLocationDescription());
            assertDoesNotThrow(h.grave::getAllGraveMessage);
        }
    }

    @Test void unavailableWorldRemindersDeliverDistinctDeadlinesAndSuppressRepeats() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var state = new DurableFeatureState(h.io, new FeaturePersistenceTest.Disk(), 8192);
            var reminders = new GraveReminderStore(state);
            FeaturePersistenceTest.await(reminders.loaded());
            when(h.plugin.getGraveReminderStore()).thenReturn(reminders);
            when(h.config.isBreakOtherGravesEnabled()).thenReturn(true);
            when(h.config.getProtectionReminderMillis()).thenReturn(60_000L);
            when(h.config.getExpiryReminderMillis()).thenReturn(60_000L);
            when(h.plugin.getTimer()).thenReturn(mock(ScheduledExecutorService.class));
            h.grave.checkTimeLimit("1m");
            h.grave.getGravesConfig().getLocation().setWorld(null);
            var listener = new GraveReminderListener(h.plugin);
            listener.start();
            try {
                assertDoesNotThrow(() -> listener.checkPlayer(h.owner));
                FeaturePersistenceTest.await(h.io.barrier());
                assertEquals(2, state.snapshot().size());
                verify(h.owner).sendMessage(argThat((String text) -> text.contains("world unavailable")
                        && text.contains("(1,64,1)") && text.contains("expires") && text.contains("protection ends")));
                listener.checkPlayer(h.owner);
                verify(h.owner, times(1)).sendMessage(any(String.class));
                verify(h.world, never()).getChunkAt(anyInt(), anyInt());
            } finally { listener.stop(); }
        }
    }
}
