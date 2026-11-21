package com.bencodez.gravestonesplus.tests.graves;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import com.bencodez.gravestonesplus.graves.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LimitDeliveryHandoffTest {
    @TempDir Path directory;
    @Test void retirementBeforeReadyUsesReservedRollbackEvenDuringWorkerShutdown() throws Exception {
        String grave="synthetic-limit";UUID recipient=UUID.randomUUID();
        var io=new FeatureIO(2);var release=new CountDownLatch(1);Thread shutdown=null;
        try {
            var ledger=new GraveClaimLedger(new DurableFeatureState(io,directory.resolve("claims.yml"),128));
            assertTrue(ClaimHarness.await(ledger.loaded()));assertTrue(ClaimHarness.await(ledger.queueLimit(grave,recipient,"synthetic-evidence")));
            var entered=new CountDownLatch(1);
            io.submit(()->{entered.countDown();try { assertTrue(release.await(5,TimeUnit.SECONDS)); } catch(InterruptedException e) { throw new AssertionError(e); }return true;});
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            var delivery=ledger.admitLimitDelivery(grave,recipient);assertNotNull(delivery);assertFalse(delivery.beginDelivery());
            var deferred=delivery.deferBeforeDelivery();assertNotNull(deferred);assertFalse(deferred.isDone());
            shutdown=new Thread(io::close,"synthetic-limit-shutdown");shutdown.start();
            release.countDown();assertTrue(ClaimHarness.await(deferred));shutdown.join(3000);assertFalse(shutdown.isAlive());
            assertTrue(io.isTerminated());assertFalse(delivery.beginDelivery());
        } finally {release.countDown();io.close();if(shutdown!=null)shutdown.join(3000);}
        try(var restartedIo=new FeatureIO()) {
            var ledger=new GraveClaimLedger(new DurableFeatureState(restartedIo,directory.resolve("claims.yml"),128));
            assertTrue(ClaimHarness.await(ledger.loaded()));assertEquals(GraveClaimLedger.Restoration.QUEUED_LIMIT,ClaimHarness.await(ledger.restoration(grave)));
        }
    }
    @Test void actorOwnershipAndRetirementHaveOneWinner() throws Exception {
        try(var io=new FeatureIO()) {
            var ledger=new GraveClaimLedger(new DurableFeatureState(io,directory.resolve("race.yml"),128));assertTrue(ClaimHarness.await(ledger.loaded()));
            for(int i=0;i<20;i++) {
                String grave="synthetic-race-"+i;UUID recipient=UUID.randomUUID();assertTrue(ClaimHarness.await(ledger.queueLimit(grave,recipient,"evidence")));
                var delivery=ledger.admitLimitDelivery(grave,recipient);assertNotNull(delivery);assertTrue(ClaimHarness.await(delivery.ready()));
                var start=new CountDownLatch(1);var actor=new CompletableFuture<Boolean>();var retired=new CompletableFuture<CompletableFuture<Boolean>>();
                Thread a=new Thread(()->{try {start.await();actor.complete(delivery.beginDelivery());}catch(Throwable error){actor.completeExceptionally(error);}});
                Thread b=new Thread(()->{try {start.await();retired.complete(delivery.deferBeforeDelivery());}catch(Throwable error){retired.completeExceptionally(error);}});
                a.start();b.start();start.countDown();a.join(3000);b.join(3000);assertFalse(a.isAlive());assertFalse(b.isAlive());
                var deferred=ClaimHarness.await(retired);
                if(ClaimHarness.await(actor)) {assertNull(deferred);assertEquals(GraveClaimLedger.Restoration.RESERVED,ClaimHarness.await(ledger.restoration(grave)));}
                else {assertNotNull(deferred);assertTrue(ClaimHarness.await(deferred));assertEquals(GraveClaimLedger.Restoration.QUEUED_LIMIT,ClaimHarness.await(ledger.restoration(grave)));}
            }
        }
    }
}
