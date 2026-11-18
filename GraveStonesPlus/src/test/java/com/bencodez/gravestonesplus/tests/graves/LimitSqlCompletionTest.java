package com.bencodez.gravestonesplus.tests.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import com.bencodez.gravestonesplus.graves.GraveClaimLedger;
import com.bencodez.gravestonesplus.storage.GraveStorageManager;
import com.bencodez.gravestonesplus.storage.SqlRecoveryJournal;

class LimitSqlCompletionTest {
    @Test void capacityRejectsBeforeGroundRewardsAndPreservesQueuedOwnerEvidence() throws Exception {
        try(var h=new ClaimHarness()) {
            var restoration=h.limitRestoration();h.groundWorld();
            var storage=mock(GraveStorageManager.class);when(storage.isMySQL()).thenReturn(true);when(h.plugin.getStorageManager()).thenReturn(storage);
            h.grave.dropAndRemoveForLimit(h.owner);h.drainGround();
            verify(storage,atLeastOnce()).reserveSqlTransition(eq(SqlRecoveryJournal.Kind.UPDATE),argThat(record->record.isDestroyed() && record.getExp()==17),eq(true));
            assertEquals(GraveClaimLedger.Restoration.QUEUED_LIMIT,ClaimHarness.await(h.ledger.restoration(h.identity())));
            verify(h.world,never()).dropItem(any(),any());verify(h.world,never()).spawn(any(),eq(org.bukkit.entity.ExperienceOrb.class));
            assertFalse(h.grave.getGravesConfig().isDestroyed());assertTrue(h.active.contains(h.grave));restoration.retire();
        }
    }
    @Test void fullPayoutWaitsForRecoverableSqlAndBothAcknowledgmentsBeforeSuccess() throws Exception {
        try(var h=new ClaimHarness()) {
            var restoration=h.limitRestoration();var orb=h.groundWorld();
            var storage=mock(GraveStorageManager.class);when(storage.isMySQL()).thenReturn(true);when(h.plugin.getStorageManager()).thenReturn(storage);
            var ticket=mock(SqlRecoveryJournal.Reservation.class);when(storage.reserveSqlTransition(any(),any(),eq(true))).thenReturn(ticket);
            var durable=new CompletableFuture<SqlRecoveryJournal.Disposition>();var written=new CompletableFuture<Boolean>();
            when(ticket.durable()).thenReturn(durable);when(ticket.commit()).thenReturn(written);
            when(h.plugin.removeGrave(eq(h.grave),same(ticket))).thenAnswer(call->{h.active.remove(h.grave);return ticket.commit();});
            h.grave.dropAndRemoveForLimit(h.owner);h.drainGround();
            verify(h.world,times(1)).dropItem(any(),eq(h.cloned));verify(orb).setExperience(17);
            assertEquals(GraveClaimLedger.Restoration.RESERVED,ClaimHarness.await(h.ledger.restoration(h.identity())));
            durable.complete(SqlRecoveryJournal.Disposition.DURABLE);h.drainGround();
            assertEquals(GraveClaimLedger.Restoration.PAID,ClaimHarness.await(h.ledger.restoration(h.identity())));
            written.complete(true);h.drainGround();verify(ticket).commit();
            h.grave.dropAndRemoveForLimit(h.owner);h.drainGround();verify(h.world,times(1)).dropItem(any(),any());restoration.retire();
        }
    }
    @Test void partialGroundPayoutCannotRequeueOrCommitTerminalSqlIntent() throws Exception {
        try(var h=new ClaimHarness()) {
            var restoration=h.limitRestoration();h.groundWorld();
            var storage=mock(GraveStorageManager.class);when(storage.isMySQL()).thenReturn(true);when(h.plugin.getStorageManager()).thenReturn(storage);
            var ticket=mock(SqlRecoveryJournal.Reservation.class);when(storage.reserveSqlTransition(any(),any(),eq(true))).thenReturn(ticket);
            doThrow(new IllegalStateException("synthetic uncertain ground delivery")).when(h.world).dropItem(any(),any());
            h.grave.dropAndRemoveForLimit(h.owner);h.drainGround();
            assertEquals(GraveClaimLedger.Restoration.RESERVED,ClaimHarness.await(h.ledger.restoration(h.identity())));
            verify(ticket,never()).commit();verify(ticket,atLeastOnce()).close();
            h.grave.dropAndRemoveForLimit(h.owner);h.drainGround();verify(h.world,times(1)).dropItem(any(),any());restoration.retire();
        }
    }
    @Test void retirementBeforeRegionDeliveryReleasesSqlAdmissionAndPreventsLatePayout() throws Exception {
        try(var h=new ClaimHarness()) {
            var restoration=h.limitRestoration();h.groundWorld();
            var storage=mock(GraveStorageManager.class);when(storage.isMySQL()).thenReturn(true);when(h.plugin.getStorageManager()).thenReturn(storage);
            var ticket=mock(SqlRecoveryJournal.Reservation.class);when(storage.reserveSqlTransition(any(),any(),eq(true))).thenReturn(ticket);
            h.grave.dropAndRemoveForLimit(h.owner);h.drain();
            h.resumeLimit();h.drain();verify(storage).reserveSqlTransition(any(),any(),eq(true));
            h.retireAcknowledgments();h.drainGround();
            verify(ticket).close();verify(ticket,never()).commit();verify(h.world,never()).dropItem(any(),any());
            assertEquals(GraveClaimLedger.Restoration.QUEUED_LIMIT,ClaimHarness.await(h.ledger.restoration(h.identity())));var restarted=h.restartLedger();assertTrue(ClaimHarness.await(restarted.loaded()));
            assertEquals(GraveClaimLedger.Restoration.QUEUED_LIMIT,ClaimHarness.await(restarted.restoration(h.identity())));restoration.retire();
        }
    }

    @Test void retirementDuringFinalEventWinsBeforeFirstWorldEffect() throws Exception {
        try(var h=new ClaimHarness()) {
            var restoration=h.limitRestoration();h.groundWorld();
            var storage=mock(GraveStorageManager.class);when(storage.isMySQL()).thenReturn(true);when(h.plugin.getStorageManager()).thenReturn(storage);
            var ticket=mock(SqlRecoveryJournal.Reservation.class);when(storage.reserveSqlTransition(any(),any(),eq(true))).thenReturn(ticket);
            doAnswer(call->{if(call.getArgument(0) instanceof com.bencodez.gravestonesplus.events.GraveRemoveEvent)h.retireAcknowledgments();return null;})
                    .when(h.events).callEvent(any());
            h.grave.dropAndRemoveForLimit(h.owner);h.drainGround();
            verify(ticket).close();verify(ticket,never()).commit();verify(h.world,never()).dropItem(any(),any());
            assertEquals(GraveClaimLedger.Restoration.QUEUED_LIMIT,ClaimHarness.await(h.ledger.restoration(h.identity())));restoration.retire();
        }
    }

}
