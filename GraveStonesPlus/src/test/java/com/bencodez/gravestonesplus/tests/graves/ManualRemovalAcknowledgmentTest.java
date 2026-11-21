package com.bencodez.gravestonesplus.tests.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.gravestonesplus.graves.*;
import com.bencodez.gravestonesplus.storage.*;
import com.bencodez.gravestonesplus.events.GraveRemoveReason;

class ManualRemovalAcknowledgmentTest {
    @TempDir Path directory;
    @ParameterizedTest @ValueSource(booleans={true,false})
    void feedbackWaitsForExactSqlAcknowledgmentAndNeverAdvertisesHeldRemoval(boolean saved) throws Exception {
        try(var h=new ClaimHarness()) {
            h.groundWorld();var storage=mock(GraveStorageManager.class);var ticket=mock(SqlRecoveryJournal.Reservation.class);
            when(storage.isMySQL()).thenReturn(true);when(h.plugin.getStorageManager()).thenReturn(storage);
            when(storage.reserveSqlTransition(any(),any(),eq(true))).thenReturn(ticket);
            var written=new CompletableFuture<Boolean>();when(ticket.commit()).thenReturn(written);
            when(h.plugin.removeGrave(eq(h.grave),same(ticket))).thenAnswer(call->{h.active.remove(h.grave);return ticket.commit();});
            h.grave.removeByPlayer(h.owner,GraveRemoveReason.MANUAL_REMOVE);h.drainGround();
            assertTrue(h.grave.getGravesConfig().isDestroyed());assertFalse(h.active.contains(h.grave));
            verify(h.owner,never()).sendMessage("\u00a7cGrave removed");
            if(saved) written.complete(true);else written.completeExceptionally(new IllegalStateException("synthetic held removal"));
            h.drainGround();verify(h.owner,times(saved?1:0)).sendMessage("\u00a7cGrave removed");
            verify(ticket,times(1)).commit();verify(h.owner,never()).giveExp(anyInt());verify(h.world,never()).dropItem(any(),any());
        }
    }
    @Test void durableRemovalOutageReplaysExactUpdateAfterActualWorkerCloseAndReopen() throws Exception {
        try(var h=new ClaimHarness();var encoding=mockStatic(InventorySerializer.class)) {
            encoding.when(()->InventorySerializer.encode(any())).thenAnswer(call->{Map<?,?> items=call.getArgument(0);return "synthetic-manual:"+items.keySet();});
            h.groundWorld();when(h.plugin.getFeatureIO()).thenReturn(h.io);
            var backend=mock(GraveStorageBackend.class);doThrow(new IllegalStateException("synthetic SQL outage")).when(backend).removeExistingGrave(any());
            var manager=new GraveStorageManager(h.plugin);
            for(var entry:Map.of("backend",(Object)backend,"type",DataStorageType.MYSQL).entrySet()) {
                var field=manager.getClass().getDeclaredField(entry.getKey());field.setAccessible(true);field.set(manager,entry.getValue());
            }
            when(h.plugin.getStorageManager()).thenReturn(manager);
            when(h.plugin.removeGrave(eq(h.grave),any(SqlRecoveryJournal.Reservation.class))).thenAnswer(call->{
                h.active.remove(h.grave);return ((SqlRecoveryJournal.Reservation)call.getArgument(1)).commit();
            });
            try(var journal=new com.bencodez.gravestonesplus.tests.storage.SqlJournalHarness(h.io,backend,manager,directory)) {
                h.grave.removeByPlayer(h.owner,GraveRemoveReason.MANUAL_REMOVE);h.drainGround();
                assertEquals(1,journal.records());assertTrue(h.grave.getGravesConfig().isDestroyed());
                verify(h.owner,never()).sendMessage("\u00a7cGrave removed");verify(h.owner,never()).giveExp(anyInt());
            }
        }
        try(var io=new FeatureIO()) {
            var backend=mock(GraveStorageBackend.class);var replayed=new CountDownLatch(1);
            doAnswer(call->{GravesConfig record=call.getArgument(0);assertTrue(record.isDestroyed());assertEquals(17,record.getExp());
                assertEquals("synthetic-manual:[4]",com.bencodez.gravestonesplus.tests.storage.SqlJournalHarness.encoded(record));
                replayed.countDown();return null;
            }).when(backend).removeExistingGrave(any());
            var journal=new SqlRecoveryJournal(io,backend,directory,ignored->{});assertTrue(ClaimHarness.await(journal.loaded()));
            assertTrue(replayed.await(3,TimeUnit.SECONDS));journal.close();verify(backend,never()).upsertGrave(any(),anyBoolean());
        }
    }
}
