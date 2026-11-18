package com.bencodez.gravestonesplus.tests.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.Test;
import com.bencodez.gravestonesplus.graves.*;
import com.bencodez.gravestonesplus.storage.*;
import com.bencodez.gravestonesplus.events.GraveRemoveReason;

class RemovalSqlAdmissionTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=GraveRemoveReason.class,names={"EXPIRED","MANUAL_REMOVE"})
    void saturatedRemovalRetainsFlagsListsQueuedEvidenceAndSameDeadline(GraveRemoveReason reason) throws Exception {
        try(var h=new ClaimHarness();var encoding=mockStatic(InventorySerializer.class)) {
            encoding.when(()->InventorySerializer.encode(any())).thenReturn("synthetic-expiry");h.bukkit.when(Bukkit::isPrimaryThread).thenReturn(true);
            when(h.plugin.getFeatureIO()).thenReturn(h.io);var backend=mock(GraveStorageBackend.class);var manager=spy(new GraveStorageManager(h.plugin));
            for(var entry:Map.of("backend",(Object)backend,"type",DataStorageType.MYSQL).entrySet()) {
                var field=manager.getClass().getDeclaredField(entry.getKey());field.setAccessible(true);field.set(manager,entry.getValue());
            }
            when(h.plugin.getStorageManager()).thenReturn(manager);
            var cleanup=mock(GraveMarkerCleanup.class);var cleanupTicket=mock(GraveMarkerCleanup.Ticket.class);
            when(cleanup.prepare(any())).thenReturn(cleanupTicket);when(h.plugin.getMarkerCleanup()).thenReturn(cleanup);
            var timer=mock(ScheduledExecutorService.class);when(h.plugin.getTimer()).thenReturn(timer);
            assertTrue(ClaimHarness.await(h.ledger.queueLimit(h.identity(),h.owner.getUniqueId(),"synthetic-evidence")));
            var pending=Grave.class.getDeclaredField("limitPayoutPending");pending.setAccessible(true);pending.setBoolean(h.grave,true);
            long deadline=System.currentTimeMillis()-1;var field=Grave.class.getDeclaredField("expiryDeadline");field.setAccessible(true);field.setLong(h.grave,deadline);
            try(var journal=new com.bencodez.gravestonesplus.tests.storage.SqlJournalHarness(h.io,backend,manager)) {
                var tickets=new ArrayList<FeatureIO.Admission>();
                try {
                    for(int i=0;i<129;i++) {var ticket=h.io.admit();assertNotNull(ticket);tickets.add(ticket);}
                    h.grave.removeGrave(reason);
                    verify(manager).reserveSqlTransition(eq(SqlRecoveryJournal.Kind.REMOVE),argThat(record->record.isDestroyed()),eq(true));
                    assertFalse(h.grave.isRemove());assertFalse(h.grave.getGravesConfig().isDestroyed());assertTrue(h.active.contains(h.grave));
                    assertTrue(h.grave.isLimitPayoutPending());assertEquals(deadline,h.grave.getExpiryDeadline());
                    verify(cleanupTicket).close();verify(cleanupTicket,never()).commit();
                    if(reason==GraveRemoveReason.EXPIRED) verify(timer).schedule(any(Runnable.class),eq(5L),eq(TimeUnit.SECONDS));
                    else verify(timer,never()).schedule(any(Runnable.class),anyLong(),any(TimeUnit.class));
                    verify(h.plugin,never()).removeGrave(any());verify(h.plugin,never()).removeGrave(any(),any());
                } finally {tickets.forEach(FeatureIO.Admission::close);}
                assertEquals(GraveClaimLedger.Restoration.QUEUED_LIMIT,ClaimHarness.await(h.ledger.restoration(h.identity())));
                assertEquals(0,journal.records());verify(backend,never()).removeExistingGrave(any());
            }
        }
    }
}
