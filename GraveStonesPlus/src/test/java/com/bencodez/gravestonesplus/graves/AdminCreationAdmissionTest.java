package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.gravestonesplus.commands.CommandLoader;

/** Test-grave creation must not destroy a real grave when scheduler admission fails. */
class AdminCreationAdmissionTest {
    private static Location prepare(GraveActionsTest.Harness h, Grave oldest, boolean drop) {
        when(h.config.getDisabledWorlds()).thenReturn(new ArrayList<>());
        when(h.config.getGraveLimit()).thenReturn(1);
        when(h.config.isDropItemsOnGraveRemoval()).thenReturn(drop);
        when(h.config.getGraveDisplayTypeEnum()).thenReturn(GraveDisplayType.CHEST);
        when(h.plugin.numberOfGraves(h.owner.getUniqueId())).thenReturn(1);
        when(h.plugin.getOldestGrave(h.owner.getUniqueId())).thenReturn(oldest);
        when(h.world.getMinHeight()).thenReturn(-64); when(h.world.getMaxHeight()).thenReturn(320);
        Block block = mock(Block.class); when(block.isEmpty()).thenReturn(true);
        when(h.world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(block);
        when(h.world.getBlockAt(any(Location.class))).thenReturn(block);
        return new Location(h.world, 2, 64, 2);
    }
    @ParameterizedTest @ValueSource(booleans = { false, true })
    void rejectedCreationKeepsExistingGraveAndNeverPublishesReplacement(boolean drop) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            Grave oldest = mock(Grave.class); Location target = prepare(h, oldest, drop);
            doThrow(new java.util.concurrent.RejectedExecutionException("region retired"))
                .when(h.scheduler).runTaskLater(eq(h.plugin), any(Runnable.class), eq(2L), eq(target));
            when(h.platform.runAtLocationTimer(eq(target), any(Runnable.class), eq(2L), eq(1L), eq(TimeUnit.SECONDS)))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("region retired"));
            var items = new HashMap<Integer, ItemStack>(Map.of(4, h.stored));
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () ->
                new CommandLoader(h.plugin).createTestGrave(h.owner.getUniqueId(), "Owner", target, items, 7, "test", h.owner));
            verify(oldest, never()).removeGrave(any()); verify(oldest, never()).dropAndRemoveForLimit(any());
            verify(h.plugin, never()).addGrave(any());
            assertEquals(Map.of(4, h.stored), items);
        }
    }
    @ParameterizedTest @ValueSource(booleans = { false, true })
    void failedLimitRemovalCancelsAdmittedCreationEvenWhenCallbackArrivesEarly(boolean drop) throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            Grave oldest = mock(Grave.class); Location target = prepare(h, oldest, drop);
            Runnable[] work = { null };
            var task = mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class);
            when(h.platform.runAtLocationTimer(eq(target), any(Runnable.class), eq(2L), eq(1L), eq(TimeUnit.SECONDS)))
                .thenAnswer(call -> { work[0] = call.getArgument(1); work[0].run(); return task; });
            if (drop) doThrow(new IllegalStateException("limit unavailable")).when(oldest).dropAndRemoveForLimit(null);
            else doThrow(new IllegalStateException("limit unavailable")).when(oldest).removeGrave(any());
            assertThrows(IllegalStateException.class, () -> new CommandLoader(h.plugin).createTestGrave(
                h.owner.getUniqueId(), "Owner", target, new HashMap<>(Map.of(4, h.stored)), 7, "test", h.owner));
            assertNotNull(work[0]); work[0].run(); work[0].run();
            verify(task, times(1)).cancel(); verify(h.plugin, never()).addGrave(any());
            verify(h.world, times(1)).getBlockAt(any(Location.class));
        }
    }
    @ParameterizedTest @ValueSource(booleans = { false, true })
    void admittedCreationRunsOnceAfterPreflightAndPreservesLimitPolicy(boolean drop) throws Exception {
        try (var h = new GraveActionsTest.Harness(); var construction = mockConstruction(Grave.class)) {
            Grave oldest = mock(Grave.class); Location target = prepare(h, oldest, drop);
            Runnable[] work = { null };
            var task = mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class);
            when(h.platform.runAtLocationTimer(eq(target), any(Runnable.class), eq(2L), eq(1L), eq(TimeUnit.SECONDS)))
                .thenAnswer(call -> {
                    work[0] = call.getArgument(1); work[0].run();
                    verify(h.plugin, never()).addGrave(any()); return task;
                });
            new CommandLoader(h.plugin).createTestGrave(h.owner.getUniqueId(), "Owner", target,
                new HashMap<>(Map.of(4, h.stored)), 7, "test", h.owner);
            if (drop) {
                verify(oldest).dropAndRemoveForLimit(null); verify(oldest, never()).removeGrave(any());
            } else {
                verify(oldest).removeGrave(com.bencodez.gravestonesplus.events.GraveRemoveReason.LIMIT_REACHED);
                verify(oldest, never()).dropAndRemoveForLimit(any());
            }
            assertTrue(construction.constructed().isEmpty());
            work[0].run(); work[0].run();
            assertEquals(1, construction.constructed().size());
            Grave created = construction.constructed().get(0);
            verify(created).loadChunk(false); verify(created).createGrave(); verify(created).createHologram();
            verify(h.plugin, times(1)).addGrave(created); verify(task, times(1)).cancel();
            verify(h.owner, times(1)).sendMessage("Generated grave for Owner at 2, 64, 2");
        }
    }
}
