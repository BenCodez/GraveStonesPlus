package com.bencodez.gravestonesplus.tests.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.bencodez.gravestonesplus.graves.FeatureIO;
import com.bencodez.gravestonesplus.storage.*;

class RecreationReservationTest {
    @TempDir Path directory;
    static <T>T await(CompletableFuture<T> future)throws Exception{return future.get(5,TimeUnit.SECONDS);}
    static GravesConfig metadata() {
        var record=new GravesConfig(UUID.randomUUID(),"",new Location(null,1,64,2),new HashMap<>(),0,"",1000,false,0,
                UUID.randomUUID(),UUID.randomUUID(),"DISPLAY_ENTITY");record.setWorldId(UUID.randomUUID());return record;
    }
    SqlRecoveryJournal journal(FeatureIO io,GraveStorageBackend backend,Path path,long budget)throws Exception {
        var constructor=SqlRecoveryJournal.class.getDeclaredConstructor(FeatureIO.class,GraveStorageBackend.class,Path.class,java.util.function.Consumer.class,int.class,long.class);
        constructor.setAccessible(true);var result=constructor.newInstance(io,backend,path,(java.util.function.Consumer<String>)ignored->{},8,budget);
        assertTrue(await(result.loaded()));await(io.barrier());return result;
    }
    long files()throws Exception {try(var paths=Files.list(directory)){return paths.count();}}
    @Test void noProvisionalWireAndGlobalPrefixWaitsForPreparedActualUuids()throws Exception {
        try(var io=new FeatureIO()) {
            var backend=mock(GraveStorageBackend.class);var journal=journal(io,backend,directory,4096);var record=spy(metadata());
            doThrow(new AssertionError("recreation metadata cannot read inventories")).when(record).getItems();
            doThrow(new IllegalStateException("synthetic SQL outage")).when(backend).saveRecreatedGrave(any());
            try(var slot=journal.reserveRecreation(record);var following=journal.reserve(SqlRecoveryJournal.Kind.FLAG,record,true)) {
                assertNotNull(slot);assertNotNull(following);following.commit();await(io.barrier());
                assertEquals(0,files());assertFalse(following.durable().isDone());verifyNoInteractions(backend);
                UUID actual=UUID.randomUUID();record.setDisplayUUID(actual);assertTrue(slot.prepareRecreation(record));
                record.setDisplayUUID(UUID.randomUUID());assertFalse(slot.prepareRecreation(record));
                assertThrows(ExecutionException.class,()->await(slot.commit()));assertEquals(SqlRecoveryJournal.Disposition.DURABLE,await(following.durable()));
                var captured=org.mockito.ArgumentCaptor.forClass(GravesConfig.class);verify(backend).saveRecreatedGrave(captured.capture());
                assertEquals(actual,captured.getValue().getDisplayUUID());verify(backend,never()).setBroken(any(),anyBoolean());
                var first=directory.resolve(String.format(Locale.ROOT,"%019d.sql",1L));assertEquals(177,Files.size(first));
                byte[] wire=Files.readAllBytes(first);assertEquals(1,java.nio.ByteBuffer.wrap(wire).getInt(8));assertEquals(SqlRecoveryJournal.Kind.RECREATE.ordinal(),wire[12]);
            }
            journal.close();
        }
    }
    @Test void committingUnpreparedSlotCancelsItAndReleasesBudget()throws Exception {
        try(var io=new FeatureIO()) {
            var backend=mock(GraveStorageBackend.class);var journal=journal(io,backend,directory,177);var record=metadata();
            var slot=journal.reserveRecreation(record);assertNotNull(slot);assertNull(journal.reserveRecreation(record));
            assertFalse(await(slot.commit()));assertEquals(SqlRecoveryJournal.Disposition.CANCELLED,await(slot.durable()));assertEquals(0,files());
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);SqlRecoveryJournal.Reservation next=null;
            while(next==null && System.nanoTime()<until){next=journal.reserveRecreation(record);if(next==null)Thread.sleep(1);}
            assertNotNull(next);next.close();journal.close();verifyNoInteractions(backend);
        }
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3})
    void invalidPreparationCancelsWithoutPublishingOrLeaking(int invalid)throws Exception {
        try(var io=new FeatureIO()) {
            var backend=mock(GraveStorageBackend.class);var journal=journal(io,backend,directory,177);var record=metadata();
            try(var slot=journal.reserveRecreation(record)) {
                assertNotNull(slot);
                if(invalid==0)record.setWorldId(UUID.randomUUID());
                if(invalid==1)record.setGraveDisplayType("legacy-value-outside-marker-enum");
                if(invalid==2)record.setDestroyed(true);
                if(invalid==3)record.setDestroyedTime(1);
                assertFalse(slot.prepareRecreation(record));assertFalse(await(slot.commit()));
                assertEquals(SqlRecoveryJournal.Disposition.CANCELLED,await(slot.durable()));assertFalse(io.hasUnsubmittedAdmissions());
                assertEquals(0,files());verifyNoInteractions(backend);
            }journal.close();
        }
    }
    @Test void encodingErrorCancelsIssuedAdmission()throws Exception {
        try(var io=new FeatureIO()) {
            var backend=mock(GraveStorageBackend.class);var journal=journal(io,backend,directory,177);var record=spy(metadata());
            try(var slot=journal.reserveRecreation(record)) {
                doThrow(new AssertionError("synthetic metadata capture error")).when(record).getDisplayUUID();
                assertThrows(AssertionError.class,()->slot.prepareRecreation(record));
                assertEquals(SqlRecoveryJournal.Disposition.CANCELLED,await(slot.durable()));assertFalse(io.hasUnsubmittedAdmissions());assertEquals(0,files());
            }journal.close();
        }
    }
    @Test void insufficientByteBudgetRejectsBeforeAdmission()throws Exception {
        try(var io=new FeatureIO()) {
            var backend=mock(GraveStorageBackend.class);var journal=journal(io,backend,directory,176);
            assertNull(journal.reserveRecreation(metadata()));assertFalse(io.hasUnsubmittedAdmissions());assertEquals(0,files());journal.close();
        }
    }
    @Test void preparedSlotCanFinishAfterCloseStarts()throws Exception {
        var io=new FeatureIO();Thread closing=null;
        try {
            var backend=mock(GraveStorageBackend.class);var journal=journal(io,backend,directory,177);var record=metadata();
            try(var slot=journal.reserveRecreation(record)) {
                assertNotNull(slot);journal.close();closing=new Thread(io::close,"synthetic-recreation-close");closing.start();
                assertTrue(slot.prepareRecreation(record));assertTrue(await(slot.commit()));closing.join(3000);assertFalse(closing.isAlive());
                verify(backend,times(1)).saveRecreatedGrave(any());assertEquals(0,files());
            }
        }finally{io.close();if(closing!=null)closing.join(3000);}
    }
    @Test void ordinaryNoMarkerWirePreservesLegacyDisplayFieldsAcrossReplay()throws Exception {
        var record=metadata();String legacy="legacy:"+"x".repeat(200);record.setGraveDisplayType(legacy);
        try(var io=new FeatureIO()) {
            var backend=mock(GraveStorageBackend.class);doThrow(new IllegalStateException("synthetic outage")).when(backend).saveRecreatedGrave(any());
            var journal=journal(io,backend,directory,4096);
            try(var slot=journal.reserve(SqlRecoveryJournal.Kind.RECREATE,record,false)) {assertNotNull(slot);assertThrows(ExecutionException.class,()->await(slot.commit()));}
            var captured=org.mockito.ArgumentCaptor.forClass(GravesConfig.class);verify(backend).saveRecreatedGrave(captured.capture());
            assertEquals(legacy,captured.getValue().getGraveDisplayType());journal.close();
        }
        try(var io=new FeatureIO()) {
            var backend=mock(GraveStorageBackend.class);journal(io,backend,directory,4096);
            var captured=org.mockito.ArgumentCaptor.forClass(GravesConfig.class);verify(backend).saveRecreatedGrave(captured.capture());
            assertEquals(legacy,captured.getValue().getGraveDisplayType());assertEquals(record.getDisplayUUID(),captured.getValue().getDisplayUUID());
            assertEquals(record.getInteractUUID(),captured.getValue().getInteractUUID());assertEquals(0,files());
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void fixedBudgetIsReleasedAfterPreparedCancellationOrSqlRetirement(boolean commit)throws Exception {
        try(var io=new FeatureIO()) {
            var backend=mock(GraveStorageBackend.class);var journal=journal(io,backend,directory,177);var record=metadata();
            record.setGraveDisplayType("CHEST");record.setDisplayUUID(null);record.setInteractUUID(null);
            try(var slot=journal.reserveRecreation(record)) {
                assertNotNull(slot);assertTrue(slot.prepareRecreation(record));
                if(commit)assertTrue(await(slot.commit()));else{slot.close();assertFalse(await(slot.commit()));}
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);SqlRecoveryJournal.Reservation next=null;
                while(next==null && System.nanoTime()<until){next=journal.reserveRecreation(record);if(next==null)Thread.sleep(1);}
                assertNotNull(next,"the full reserved 177 bytes must be released, not merely the smaller final wire");next.close();
            }journal.close();
        }
    }
    @Test void fatalPublicationCancelsUnpreparedLateSlotWithoutLeakingItsAdmission()throws Exception {
        try(var io=new FeatureIO(4)) {
            var backend=mock(GraveStorageBackend.class);var journal=journal(io,backend,directory,4096);var record=metadata();
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var busy=io.submit(()->{entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException error){throw new AssertionError(error);}return true;});
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            try(var first=journal.reserve(SqlRecoveryJournal.Kind.FLAG,record,true);var late=journal.reserveRecreation(record)) {
                assertNotNull(first);assertNotNull(late);Files.createDirectory(directory.resolve(String.format(Locale.ROOT,"%019d.sql",1L)));
                first.commit();release.countDown();await(busy);assertEquals(SqlRecoveryJournal.Disposition.HELD,await(first.durable()));
                assertFalse(late.prepareRecreation(record));assertFalse(await(late.commit()));
                assertEquals(SqlRecoveryJournal.Disposition.CANCELLED,await(late.durable()));assertFalse(io.hasUnsubmittedAdmissions());
            }finally{release.countDown();journal.close();}
        }
    }
    @Test void preparationAndCommitHaveOneWireOwnershipWinner()throws Exception {
        for(int i=0;i<20;i++)try(var io=new FeatureIO()) {
            var backend=mock(GraveStorageBackend.class);var journal=journal(io,backend,directory.resolve("race"+i),177);var record=metadata();
            try(var slot=journal.reserveRecreation(record)) {
                assertNotNull(slot);var start=new CountDownLatch(1);var prepared=new CompletableFuture<Boolean>();var committed=new CompletableFuture<Boolean>();
                Thread owner=new Thread(()->{try{start.await();prepared.complete(slot.prepareRecreation(record));}catch(Throwable failure){prepared.completeExceptionally(failure);}});
                Thread commit=new Thread(()->{try{start.await();committed.complete(await(slot.commit()));}catch(Throwable failure){committed.completeExceptionally(failure);}});
                owner.start();commit.start();start.countDown();owner.join(3000);commit.join(3000);assertFalse(owner.isAlive());assertFalse(commit.isAlive());
                boolean ready=await(prepared);assertEquals(ready,await(committed));
                verify(backend,times(ready?1:0)).saveRecreatedGrave(any());assertFalse(io.hasUnsubmittedAdmissions());
            }journal.close();
        }
    }

    @Test void preparationFreezesValidatedStringsWithoutReReadingLiveValues()throws Exception {
        try(var io=new FeatureIO()) {
            var backend=mock(GraveStorageBackend.class);var journal=journal(io,backend,directory,177);var record=spy(metadata());
            try(var slot=journal.reserveRecreation(record)) {
                doReturn("CHEST","changed-".repeat(1000)).when(record).getGraveDisplayType();
                doReturn("","synthetic changed name").when(record).getPlayerName();
                doReturn("","synthetic changed death").when(record).getDeathMessage();
                assertTrue(slot.prepareRecreation(record));assertTrue(await(slot.commit()));
                var captured=org.mockito.ArgumentCaptor.forClass(GravesConfig.class);verify(backend).saveRecreatedGrave(captured.capture());
                assertEquals("CHEST",captured.getValue().getGraveDisplayType());assertEquals("",captured.getValue().getPlayerName());assertEquals("",captured.getValue().getDeathMessage());
            }journal.close();
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void failedPreparationOwnsCancellationBeforeAnotherPreparationCanWin(boolean error)throws Exception {
        try(var io=new FeatureIO()) {
            var settling=new CountDownLatch(1);var release=new CountDownLatch(1);
            var proxy=mock(FeatureIO.class,org.mockito.AdditionalAnswers.delegatesTo(io));
            doAnswer(call->{
                var original=io.admit();if(original==null)return null;
                var wrapped=mock(FeatureIO.Admission.class,org.mockito.AdditionalAnswers.delegatesTo(original));
                doAnswer(unused->{settling.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));original.close();return null;}).when(wrapped).close();
                return wrapped;
            }).when(proxy).admit();
            var backend=mock(GraveStorageBackend.class);var journal=journal(proxy,backend,directory,177);var record=metadata();
            var slot=spy(journal.reserveRecreation(record));assertNotNull(slot);
            // On the old implementation, stop before close acquires the journal monitor.
            doAnswer(call->{settling.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));call.callRealMethod();return null;}).when(slot).close();
            var invalid=spy(record);
            if(error)doThrow(new AssertionError("synthetic preparation failure")).when(invalid).getDisplayUUID();
            else invalid.setDestroyed(true);
            var failed=new CompletableFuture<Boolean>();
            Thread first=new Thread(()->{try{failed.complete(slot.prepareRecreation(invalid));}catch(Throwable failure){failed.completeExceptionally(failure);}},"synthetic-failed-preparation");
            first.start();
            try {
                assertTrue(settling.await(3,TimeUnit.SECONDS));
                assertFalse(slot.prepareRecreation(record),"failed preparation must already own cancellation while settlement is delayed");
            }finally{release.countDown();first.join(3000);}
            assertFalse(first.isAlive());
            if(error)assertInstanceOf(AssertionError.class,assertThrows(ExecutionException.class,()->await(failed)).getCause());
            else assertFalse(await(failed));
            assertFalse(await(slot.commit()));assertEquals(SqlRecoveryJournal.Disposition.CANCELLED,await(slot.durable()));
            assertFalse(io.hasUnsubmittedAdmissions());assertEquals(0,files());verifyNoInteractions(backend);journal.close();
        }
    }

}
