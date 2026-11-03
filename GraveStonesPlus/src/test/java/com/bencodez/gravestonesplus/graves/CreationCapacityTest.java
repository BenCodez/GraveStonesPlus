package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.bukkit.*;
import org.junit.jupiter.api.Test;
import com.bencodez.gravestonesplus.storage.*;

class CreationCapacityTest {
    private GravesConfig record(GraveActionsTest.Harness h, long time) {
        return new GravesConfig(h.owner.getUniqueId(), "Owner", new Location(h.world, 3, 64, 3),
                new HashMap<>(Map.of(4, h.stored)), 7, "test", time, false, 0, null, null, "CHEST");
    }
    private GraveRestoration restoration(GraveActionsTest.Harness h) {
        h.active.clear(); when(h.config.getGraveLimit()).thenReturn(1);
        when(h.plugin.numberOfGraves(any())).thenCallRealMethod();
        when(h.plugin.getOldestGrave(any())).thenCallRealMethod();
        return GraveActionsTest.limitRestoration(h);
    }
    @Test void repeatedAdmissionsReserveDistinctOldestAndKeepOneLogicalSlot() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var restoration = restoration(h);
            var first = record(h, 100); var second = record(h, 200); var third = record(h, 300);
            assertEquals(0, restoration.reserveCreation(first).priorGraves());
            var two = restoration.reserveCreation(second);
            assertEquals(1, two.priorGraves()); assertSame(first, two.replacement().getGravesConfig());
            assertEquals(1, h.plugin.numberOfGraves(h.owner.getUniqueId()));
            var three = restoration.reserveCreation(third);
            assertEquals(1, three.priorGraves()); assertSame(second, three.replacement().getGravesConfig());
            assertNotSame(two.replacement(), three.replacement());
            assertEquals(1, h.plugin.numberOfGraves(h.owner.getUniqueId()));
            assertTrue(h.active.isEmpty());
            verify(h.world, never()).getChunkAt(anyInt(), anyInt());
            verify(h.owner, never()).giveExp(anyInt());
            // Failed later admission restores its own selection; earlier accepted work remains.
            restoration.releaseCreation(third); restoration.releaseCreation(third);
            assertFalse(restoration.isCreationReplacement(three.replacement()));
            assertTrue(restoration.isCreationReplacement(two.replacement()));
            assertEquals(1, h.plugin.numberOfGraves(h.owner.getUniqueId()));
        }
    }
    @Test void dropReplacementRejectsFurtherAdmissionsBeforeAndAfterFailedSave() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var restoration = restoration(h);
            when(h.config.isDropItemsOnGraveRemoval()).thenReturn(true);
            h.active.add(h.grave);
            var next = record(h, h.grave.getGravesConfig().getTime() + 1);
            var later = record(h, next.getTime() + 1);
            var selected = restoration.reserveCreation(next);
            assertSame(h.grave, selected.replacement());
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> restoration.reserveCreation(later));
            h.claimDisk.fail = true;
            assertTrue(h.grave.admitLimitRemoval(h.owner), "Admission reserves work, not a successful save");
            Grave created = restoration.creationGrave(next); h.active.add(created); restoration.completeCreation(next);
            h.drain();
            assertEquals(2, h.plugin.numberOfGraves(h.owner.getUniqueId()));
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> restoration.reserveCreation(later));
            assertFalse(h.grave.getGravesConfig().isDestroyed()); assertFalse(next.isDestroyed());
            verify(h.world, never()).dropItem(any(), any());
        }
    }
    @Test void fullPersistenceWorkerRejectsLimitAdmissionWithoutBlockingOrRemovingContents() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            GraveActionsTest.limitRestoration(h);
            var tickets = new ArrayList<FeatureIO.Admission>();
            try {
                FeatureIO.Admission ticket;
                while ((ticket = h.io.admit()) != null) tickets.add(ticket);
                assertFalse(h.grave.admitLimitRemoval(h.owner));
                assertFalse(h.grave.getGravesConfig().isDestroyed());
                assertNull(h.ledger.status(h.identity()));
                verify(h.world, never()).dropItem(any(), any());
            } finally { tickets.forEach(FeatureIO.Admission::close); }
            assertTrue(h.grave.admitLimitRemoval(h.owner)); h.drain();
            assertTrue(h.ledger.status(h.identity()).startsWith("QUEUED_LIMIT:"));
        }
    }
    @Test void schedulerRejectionReleasesNewAdmissionAndLeavesExistingGraveSelectable() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var restoration = restoration(h); h.active.add(h.grave);
            var record = record(h, h.grave.getGravesConfig().getTime() + 1);
            var creation = new GraveCreationTask(h.plugin, record, grave -> fail("Rejected task ran"));
            when(h.platform.runAtLocationTimer(any(Location.class), any(Runnable.class), eq(2L), eq(1L), eq(TimeUnit.SECONDS)))
                    .thenThrow(new java.util.concurrent.RejectedExecutionException("retired region"));
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> creation.schedule(record.getLocation()));
            creation.run();
            assertEquals(1, h.plugin.numberOfGraves(h.owner.getUniqueId()));
            assertSame(h.grave, h.plugin.getOldestGrave(h.owner.getUniqueId()));
            assertFalse(restoration.hasPending(GraveIdentity.of(record)));
            assertFalse(h.grave.isRemove()); assertFalse(h.grave.getGravesConfig().isDestroyed());
            verify(h.plugin, never()).removeGrave(any());
        }
    }
    @Test void delayedPublicationUsesSelectedWrapperOnceAndReleasesCapacityReservation() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var restoration = restoration(h); var record = record(h, 100);
            var observed = new ArrayList<Grave>();
            var creation = new GraveCreationTask(h.plugin, record, grave -> { observed.add(grave); h.active.add(grave); });
            var task = mock(com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask.class);
            when(h.platform.runAtLocationTimer(any(Location.class), any(Runnable.class), eq(2L), eq(1L), eq(TimeUnit.SECONDS)))
                    .thenReturn(task);
            creation.schedule(record.getLocation());
            Grave pending = restoration.pendingActiveForLimits().getFirst();
            assertFalse(h.plugin.isLiveGrave(pending));
            creation.run(); assertTrue(observed.isEmpty(), "Callback cannot run before preflight acceptance");
            creation.accept(); creation.run(); creation.run(); creation.cancel();
            assertEquals(List.of(pending), observed);
            assertFalse(restoration.hasPending(GraveIdentity.of(record)));
            assertEquals(1, h.plugin.numberOfGraves(h.owner.getUniqueId()));
            verify(task, times(1)).cancel();
        }
    }
    @Test void reservationCapacityRejectsOverflowAndReclaimsOnlyReleasedIdentity() throws Exception {
        try (var h = new GraveActionsTest.Harness()) {
            var restoration = GraveActionsTest.limitRestoration(h);
            when(h.config.getGraveLimit()).thenReturn(Integer.MAX_VALUE);
            var first = record(h, 0);
            restoration.reserveCreation(first);
            for (int i = 1; i < 4096; i++) restoration.reserveCreation(record(h, i));
            var overflow = record(h, 4096);
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> restoration.reserveCreation(overflow));
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> restoration.reserveCreation(first));
            restoration.releaseCreation(first);
            assertDoesNotThrow(() -> restoration.reserveCreation(overflow));
            assertTrue(restoration.hasPending(GraveIdentity.of(overflow)));
            assertFalse(restoration.hasPending(GraveIdentity.of(first)));
        }
    }
}
