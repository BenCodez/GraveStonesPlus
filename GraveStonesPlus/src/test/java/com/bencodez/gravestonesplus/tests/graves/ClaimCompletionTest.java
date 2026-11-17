package com.bencodez.gravestonesplus.tests.graves;

import com.bencodez.gravestonesplus.graves.ClaimResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;

class ClaimCompletionTest {
    @Test void waitsForDurableReservationThenAcknowledgedPayoutExactlyOnce() throws Exception {
        try (var h = new ClaimHarness()) {
            h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
            h.io.submit(() -> {
                entered.countDown();
                try { if (!resume.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
                return true;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var claim = h.grave.claimAsync(h.owner);
            try {
                verify(h.events).callEvent(any(com.bencodez.gravestonesplus.events.GraveClaimEvent.class));
                assertFalse(claim.isDone()); verify(h.owner, never()).giveExp(anyInt());
                verify(h.ownerInventory, never()).setItem(anyInt(), any());
                assertEquals(ClaimResult.HELD, h.grave.claimAsync(h.owner).get(2, TimeUnit.SECONDS));
            } finally { resume.countDown(); }
            h.drain(); assertEquals(ClaimResult.COMPLETED, ClaimHarness.await(claim));
            verify(h.owner, times(1)).giveExp(17); verify(h.ownerInventory, times(1)).setItem(4, h.cloned);
            assertTrue(h.grave.getGravesConfig().isDestroyed()); assertTrue(h.active.isEmpty());
            assertEquals(ClaimResult.DENIED, ClaimHarness.await(h.grave.claimAsync(h.owner)));
        }
    }
    @Test void paidWriteMustAcknowledgeBeforeSuccessfulCompletion() throws Exception {
        for (boolean succeeds : new boolean[] {false, true}) try (var h = new ClaimHarness()) {
            var ledger = spy(h.ledger); var paid = new CompletableFuture<Boolean>();
            doReturn(paid).when(ledger).paid(anyString(), any()); when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            var claim = h.grave.claimAsync(h.owner); h.drain();
            verify(h.owner).giveExp(17); assertFalse(claim.isDone());
            paid.complete(succeeds); h.drain();
            assertEquals(succeeds ? ClaimResult.COMPLETED : ClaimResult.HELD, ClaimHarness.await(claim));
            assertTrue(ledger.isBlocked(h.identity()));
        }
    }
    @Test void synchronousPaidAckNeverRunsIntegrationContinuationInsideGameplayLocks() throws Exception {
        try (var h = new ClaimHarness()) {
            var ledger = spy(h.ledger); doReturn(CompletableFuture.completedFuture(true)).when(ledger).paid(anyString(), any());
            when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            var result = h.grave.claimAsync(h.owner).thenApply(outcome -> {
                assertFalse(Thread.holdsLock(h.grave)); assertFalse(Thread.holdsLock(h.access)); return outcome;
            });
            h.drain(); assertEquals(ClaimResult.COMPLETED, ClaimHarness.await(result));
            verify(h.owner).giveExp(17);
        }
    }
    @Test void synchronousCancellationPaysNothingAndReportsDenied() throws Exception {
        try (var h = new ClaimHarness()) {
            h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            doAnswer(call -> { if (call.getArgument(0) instanceof com.bencodez.gravestonesplus.events.GraveClaimEvent event)
                    event.setCancelled(true); return null; }).when(h.events).callEvent(any());
            var claim = h.grave.claimAsync(h.owner);
            assertEquals(ClaimResult.DENIED, claim.get(2, TimeUnit.SECONDS));
            assertFalse(h.ledger.isBlocked(h.identity())); verify(h.owner, never()).giveExp(anyInt());
        }
    }
    @Test void uncertainReservationReportsHeldWithoutItemsOrXp() throws Exception {
        try (var h = new ClaimHarness()) {
            h.failClaimDisk();
            var claim = h.grave.claimAsync(h.owner); h.drain();
            assertEquals(ClaimResult.HELD, ClaimHarness.await(claim));
            assertTrue(h.ledger.isBlocked(h.identity())); verify(h.owner, never()).giveExp(anyInt());
            verify(h.ownerInventory, never()).setItem(anyInt(), any());
        }
    }
    @Test void observerCancellationCannotCancelInternalPayout() throws Exception {
        try (var h = new ClaimHarness()) {
            var observed = h.grave.claimAsync(h.owner); assertTrue(observed.cancel(false));
            h.drain(); verify(h.owner, times(1)).giveExp(17);
            assertTrue(h.grave.getGravesConfig().isDestroyed()); assertTrue(h.ledger.isBlocked(h.identity()));
        }
    }
    @Test void authorizationExceptionAfterReservationIsAcknowledgedAsHeld() throws Exception {
        try (var h = new ClaimHarness()) {
            when(h.plugin.canRecover(h.grave, h.owner)).thenAnswer(call -> {
                if (h.ledger.isBlocked(h.identity())) throw new IllegalStateException("synthetic permission failure");
                return true;
            });
            var observed = h.grave.claimAsync(h.owner); h.drain();
            assertEquals(ClaimResult.HELD, ClaimHarness.await(observed));
            assertTrue(h.ledger.isBlocked(h.identity())); verify(h.owner, never()).giveExp(anyInt());
            verify(h.ownerInventory, never()).setItem(anyInt(), any());
        }
    }
    @Test void disconnectAfterReservationCancelsBeforePayoutAndAcknowledgesDenied() throws Exception {
        try (var h = new ClaimHarness()) {
            var observed = h.grave.claimAsync(h.owner);
            h.entityWork.remove().run(); ClaimHarness.await(h.io.barrier());
            when(h.owner.isOnline()).thenReturn(false); h.drain();
            assertEquals(ClaimResult.DENIED, ClaimHarness.await(observed));
            assertFalse(h.ledger.isBlocked(h.identity())); verify(h.owner, never()).giveExp(anyInt());
            verify(h.ownerInventory, never()).setItem(anyInt(), any());
        }
    }
    @Test void retirementBeforeQueuedActorCompletesHeldWithoutLatePayout() throws Exception {
        try (var h = new ClaimHarness()) {
            var observed = h.grave.claimAsync(h.owner); h.retireAcknowledgments();
            assertEquals(ClaimResult.HELD, ClaimHarness.await(observed));
            h.drain(); verify(h.owner, never()).giveExp(anyInt());
            verify(h.ownerInventory, never()).setItem(anyInt(), any()); assertFalse(h.ledger.isBlocked(h.identity()));
        }
    }
    @Test void partialPayoutFailureReportsHeldAndNeverAutomaticallyReplays() throws Exception {
        try (var h = new ClaimHarness()) {
            doThrow(new IllegalStateException("synthetic inventory failure")).when(h.ownerInventory).setItem(4, h.cloned);
            var observed = h.grave.claimAsync(h.owner); h.drain();
            assertEquals(ClaimResult.HELD, ClaimHarness.await(observed));
            assertTrue(h.ledger.isBlocked(h.identity()));
            var retry = h.grave.claimAsync(h.owner); h.drain();
            assertEquals(ClaimResult.HELD, ClaimHarness.await(retry));
            verify(h.ownerInventory, times(1)).setItem(4, h.cloned); verify(h.owner, never()).giveExp(anyInt());
        }
    }
}
