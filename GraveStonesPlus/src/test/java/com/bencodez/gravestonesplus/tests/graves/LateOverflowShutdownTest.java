package com.bencodez.gravestonesplus.tests.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import com.bencodez.gravestonesplus.graves.*;
import com.bencodez.gravestonesplus.storage.*;

class LateOverflowShutdownTest {
    @TempDir Path directory;
    @Test void lateSuccessPublishesPaidDuringCloseAndSurvivesReopen() throws Exception { runScenario(true); }
    @Test void lateExceptionPreservesReservedDuringCloseAndReopen() throws Exception { runScenario(false); }
    private void runScenario(boolean acknowledged) throws Exception {
        String identity;UUID recipient;
        try(var h=new ClaimHarness();var encoding=mockStatic(InventorySerializer.class)) {
            encoding.when(()->InventorySerializer.encode(any())).thenReturn("synthetic-late-overflow");
            identity=h.identity();recipient=h.owner.getUniqueId();when(h.plugin.getFeatureIO()).thenReturn(h.io);
            var ledger=new GraveClaimLedger(new DurableFeatureState(h.io,directory.resolve("claims.yml"),128));
            assertTrue(ClaimHarness.await(ledger.loaded()));when(h.plugin.getGraveClaimLedger()).thenReturn(ledger);
            var backend=mock(GraveStorageBackend.class);var manager=new GraveStorageManager(h.plugin);
            for(var entry:Map.of("backend",(Object)backend,"type",DataStorageType.MYSQL).entrySet()) {
                var field=manager.getClass().getDeclaredField(entry.getKey());field.setAccessible(true);field.set(manager,entry.getValue());
            }
            when(h.plugin.getStorageManager()).thenReturn(manager);
            when(h.plugin.removeGrave(eq(h.grave),any(SqlRecoveryJournal.Reservation.class))).thenAnswer(call->{
                h.active.remove(h.grave);return ((SqlRecoveryJournal.Reservation)call.getArgument(1)).commit();
            });
            var overflow=new CompletableFuture<Void>();var occupied=mock(org.bukkit.inventory.ItemStack.class);var material=h.cloned.getType();
            when(occupied.getType()).thenReturn(material);when(h.ownerInventory.getItem(4)).thenReturn(occupied);
            when(h.ownerInventory.addItem(h.cloned)).thenReturn(new HashMap<>(Map.of(0,h.cloned)));
            var type=h.plugin.getClass().getMethod("getFullInventoryHandler").getReturnType();
            Object handler=mock(type,call->call.getMethod().getName().equals("giveItemAsync") ? overflow : org.mockito.Answers.RETURNS_DEFAULTS.answer(call));
            doReturn(handler).when(h.plugin).getFullInventoryHandler();
            try(var journal=new com.bencodez.gravestonesplus.tests.storage.SqlJournalHarness(h.io,backend,manager,directory.resolve("sql"))) {
                Thread closing=null;
                try {
                    var observed=h.grave.claimAsync(h.owner);h.drain();verify(h.owner,times(1)).giveExp(17);
                    h.retireAcknowledgments();assertEquals(ClaimResult.HELD,ClaimHarness.await(observed));
                    manager.close();closing=new Thread(h.io::close,"synthetic-overflow-worker-close");closing.start();
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);boolean started=false;
                    while(System.nanoTime()<deadline) {
                        var admission=h.io.admit();if(admission==null) {started=true;break;}admission.close();Thread.sleep(2);
                    }
                    assertTrue(started,"worker must reject fresh admissions before acknowledgement");
                    if(acknowledged) overflow.complete(null);else overflow.completeExceptionally(new IllegalStateException("synthetic uncertain overflow"));
                    closing.join(5000);assertFalse(closing.isAlive());assertTrue(h.io.isTerminated());
                    assertEquals(acknowledged ? GraveClaimLedger.Restoration.PAID : GraveClaimLedger.Restoration.RESERVED,
                            ledger.status(identity).startsWith("PAID:") ? GraveClaimLedger.Restoration.PAID : GraveClaimLedger.Restoration.RESERVED);
                    verify(backend,times(acknowledged ? 1 : 0)).updateExistingGrave(any(),eq(true));verify(backend,times(1)).close();
                    assertFalse(h.io.hasUnsubmittedAdmissions());
                } finally {
                    overflow.completeExceptionally(new IllegalStateException("synthetic fixture teardown"));
                    if(closing!=null)closing.join(5000);
                }
            }
        }
        try(var io=new FeatureIO()) {
            var ledger=new GraveClaimLedger(new DurableFeatureState(io,directory.resolve("claims.yml"),128));
            assertTrue(ClaimHarness.await(ledger.loaded()));assertTrue(ledger.isBlocked(identity));
            assertEquals(acknowledged ? GraveClaimLedger.Restoration.PAID : GraveClaimLedger.Restoration.RESERVED,
                    ClaimHarness.await(ledger.restoration(identity)));
            assertFalse(ClaimHarness.await(ledger.reserve(identity,recipient)));
        }
    }
}
