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
    @Test void sqlCapacityRejectionPrecedesLedgerAndEveryRewardMutation() throws Exception {
        try (var h = new ClaimHarness()) {
            var storage = mock(com.bencodez.gravestonesplus.storage.GraveStorageManager.class);
            when(storage.isMySQL()).thenReturn(true); when(h.plugin.getStorageManager()).thenReturn(storage);
            var result = h.grave.claimAsync(h.owner); h.drain();
            assertEquals(ClaimResult.FAILED, ClaimHarness.await(result));
            assertFalse(h.ledger.isBlocked(h.identity())); assertFalse(h.grave.getGravesConfig().isDestroyed());
            assertTrue(h.active.contains(h.grave)); verify(h.owner,never()).giveExp(anyInt());
            verify(h.ownerInventory,never()).setItem(anyInt(),any());
            verify(storage).reserveSqlTransition(eq(com.bencodez.gravestonesplus.storage.SqlRecoveryJournal.Kind.UPDATE),
                    argThat(record -> record.isDestroyed() && record.getExp()==17 && !record.getItems().isEmpty()),eq(true));
        }
    }
    @Test void sqlCompletionIsReservedBeforePayoutAndHeldAcknowledgmentCannotRepay() throws Exception {
        for (boolean succeeds : new boolean[] {false,true}) try (var h = new ClaimHarness()) {
            var storage = mock(com.bencodez.gravestonesplus.storage.GraveStorageManager.class);
            var ticket = mock(com.bencodez.gravestonesplus.storage.SqlRecoveryJournal.Reservation.class);
            var written = new CompletableFuture<Boolean>(); when(ticket.commit()).thenReturn(written);
            when(storage.isMySQL()).thenReturn(true); when(h.plugin.getStorageManager()).thenReturn(storage);
            when(storage.reserveSqlTransition(any(),any(),eq(true))).thenAnswer(call -> {
                verify(h.owner,never()).giveExp(anyInt()); assertFalse(h.grave.getGravesConfig().isDestroyed()); return ticket;
            });
            when(h.plugin.removeGrave(eq(h.grave),same(ticket))).thenAnswer(call -> { h.active.remove(h.grave); return ticket.commit(); });
            var result = h.grave.claimAsync(h.owner); h.drain();
            verify(h.owner,times(1)).giveExp(17); verify(ticket).commit(); assertFalse(result.isDone());
            if(succeeds) written.complete(true); else written.completeExceptionally(new IllegalStateException("synthetic SQL outage"));
            h.drain(); assertEquals(succeeds ? ClaimResult.COMPLETED : ClaimResult.HELD,ClaimHarness.await(result));
            assertTrue(h.ledger.isBlocked(h.identity()));
            var retry = h.grave.claimAsync(h.owner); h.drain();
            assertNotEquals(ClaimResult.COMPLETED,ClaimHarness.await(retry));
            verify(h.owner,times(1)).giveExp(17); verify(ticket).close();
        }
    }
    @Test void retiredClaimReleasesReservedSqlCapacityWithoutCommitting() throws Exception {
        try (var h = new ClaimHarness()) {
            var storage = mock(com.bencodez.gravestonesplus.storage.GraveStorageManager.class);
            var ticket = mock(com.bencodez.gravestonesplus.storage.SqlRecoveryJournal.Reservation.class);
            when(storage.isMySQL()).thenReturn(true); when(h.plugin.getStorageManager()).thenReturn(storage);
            when(storage.reserveSqlTransition(any(),any(),eq(true))).thenReturn(ticket);
            var result = h.grave.claimAsync(h.owner);
            h.entityWork.remove().run(); h.retireAcknowledgments(); h.drain();
            assertEquals(ClaimResult.HELD,ClaimHarness.await(result));
            verify(ticket).close(); verify(ticket,never()).commit(); verify(h.owner,never()).giveExp(anyInt());
        }
    }

    @Test void actualClaimAtFeatureCapacityPlusOnePreservesRewardsAndOwnerEvidence() throws Exception {
        try (var h = new ClaimHarness(); var encoding = mockStatic(com.bencodez.gravestonesplus.storage.InventorySerializer.class)) {
            encoding.when(() -> com.bencodez.gravestonesplus.storage.InventorySerializer.encode(any())).thenReturn("synthetic-claim");
            h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            var backend = mock(com.bencodez.gravestonesplus.storage.GraveStorageBackend.class);
            when(h.plugin.getFeatureIO()).thenReturn(h.io);
            var manager = spy(new com.bencodez.gravestonesplus.storage.GraveStorageManager(h.plugin));
            for(var entry : java.util.Map.of("backend",(Object)backend,"type",com.bencodez.gravestonesplus.storage.DataStorageType.MYSQL).entrySet()) {
                var field = manager.getClass().getDeclaredField(entry.getKey()); field.setAccessible(true); field.set(manager,entry.getValue());
            }
            when(h.plugin.getStorageManager()).thenReturn(manager);
            try(var journal = new com.bencodez.gravestonesplus.tests.storage.SqlJournalHarness(h.io,backend,manager)) {
                var tickets = new java.util.ArrayList<com.bencodez.gravestonesplus.graves.FeatureIO.Admission>();
                try {
                    for(int i=0;i<129;i++) { var ticket=h.io.admit(); assertNotNull(ticket); tickets.add(ticket); }
                    assertNull(h.io.admit());
                    assertEquals(ClaimResult.FAILED,ClaimHarness.await(h.grave.claimAsync(h.owner)));
                    verify(manager).reserveSqlTransition(eq(com.bencodez.gravestonesplus.storage.SqlRecoveryJournal.Kind.UPDATE),any(),eq(true));
                    assertFalse(h.ledger.isBlocked(h.identity())); assertFalse(h.grave.getGravesConfig().isDestroyed());
                    verify(h.owner,never()).giveExp(anyInt()); verify(h.ownerInventory,never()).setItem(anyInt(),any());
                    verify(backend,never()).updateExistingGrave(any(),anyBoolean()); assertEquals(0,journal.records());
                } finally { tickets.forEach(com.bencodez.gravestonesplus.graves.FeatureIO.Admission::close); }
            }
        }
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path sqlDirectory;
    @Test void actualSqlOutageAfterPayoutRetainsTypedIntentAndPaidOwnerReceiptAcrossRestart() throws Exception {
        String identity;
        java.util.UUID recipient;
        try (var h = new ClaimHarness(); var encoding = mockStatic(com.bencodez.gravestonesplus.storage.InventorySerializer.class)) {
            encoding.when(() -> com.bencodez.gravestonesplus.storage.InventorySerializer.encode(any())).thenReturn("synthetic-claim");
            h.grave.getGravesConfig().setWorldId(java.util.UUID.randomUUID());
            identity=h.identity(); recipient=h.owner.getUniqueId();
            var ledger=new com.bencodez.gravestonesplus.graves.GraveClaimLedger(
                    new com.bencodez.gravestonesplus.graves.DurableFeatureState(h.io,sqlDirectory.resolve("claims.yml"),128));
            assertTrue(ClaimHarness.await(ledger.loaded())); when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            when(h.plugin.getFeatureIO()).thenReturn(h.io);
            var backend=mock(com.bencodez.gravestonesplus.storage.GraveStorageBackend.class);
            doThrow(new IllegalStateException("synthetic SQL outage")).when(backend).updateExistingGrave(any(),eq(true));
            var manager=new com.bencodez.gravestonesplus.storage.GraveStorageManager(h.plugin);
            for(var entry : java.util.Map.of("backend",(Object)backend,"type",com.bencodez.gravestonesplus.storage.DataStorageType.MYSQL).entrySet()) {
                var field=manager.getClass().getDeclaredField(entry.getKey());field.setAccessible(true);field.set(manager,entry.getValue());
            }
            when(h.plugin.getStorageManager()).thenReturn(manager);
            when(h.plugin.removeGrave(eq(h.grave),any(com.bencodez.gravestonesplus.storage.SqlRecoveryJournal.Reservation.class)))
                    .thenAnswer(call -> { h.active.remove(h.grave); return ((com.bencodez.gravestonesplus.storage.SqlRecoveryJournal.Reservation)call.getArgument(1)).commit(); });
            try(var journal=new com.bencodez.gravestonesplus.tests.storage.SqlJournalHarness(h.io,backend,manager,sqlDirectory.resolve("sql"))) {
                var result=h.grave.claimAsync(h.owner);h.drain();assertEquals(ClaimResult.HELD,ClaimHarness.await(result));
                verify(h.owner,times(1)).giveExp(17);assertEquals(1,journal.records());
                assertEquals(com.bencodez.gravestonesplus.graves.GraveClaimLedger.Restoration.PAID,ClaimHarness.await(ledger.restoration(identity)));
                verify(backend,never()).upsertGrave(any(),anyBoolean());
            }
        }
        try(var io=new com.bencodez.gravestonesplus.graves.FeatureIO()) {
            var ledger=new com.bencodez.gravestonesplus.graves.GraveClaimLedger(
                    new com.bencodez.gravestonesplus.graves.DurableFeatureState(io,sqlDirectory.resolve("claims.yml"),128));
            assertTrue(ClaimHarness.await(ledger.loaded()));assertTrue(ledger.isBlocked(identity));
            assertEquals(com.bencodez.gravestonesplus.graves.GraveClaimLedger.Restoration.PAID,ClaimHarness.await(ledger.restoration(identity)));
            assertFalse(ClaimHarness.await(ledger.reserve(identity,recipient)));
            var backend=mock(com.bencodez.gravestonesplus.storage.GraveStorageBackend.class);var replay=new java.util.concurrent.CountDownLatch(1);
            doAnswer(call -> { com.bencodez.gravestonesplus.storage.GravesConfig record=call.getArgument(0);
                assertTrue(record.isDestroyed());assertEquals(17,record.getExp());replay.countDown();return null;
            }).when(backend).updateExistingGrave(any(),eq(true));
            var journal=new com.bencodez.gravestonesplus.storage.SqlRecoveryJournal(io,backend,sqlDirectory.resolve("sql"),ignored->{});
            assertTrue(ClaimHarness.await(journal.loaded()));assertTrue(replay.await(3,TimeUnit.SECONDS));
            journal.close();verify(backend,never()).upsertGrave(any(),anyBoolean());
        }
    }

}
