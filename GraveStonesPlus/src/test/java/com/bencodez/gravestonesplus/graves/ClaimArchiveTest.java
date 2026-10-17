package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaimArchiveTest {
    @TempDir Path directory;
    private final UUID recipient = UUID.randomUUID();
    @Test void completedClaimsDoNotExhaustActiveCapacityAndEvictedIdsStillCannotReplay() throws Exception {
        var disk = new FeaturePersistenceTest.Disk();
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, disk, 2);
            var ledger = new GraveClaimLedger(state, directory.resolve("archive"));
            assertTrue(FeaturePersistenceTest.await(ledger.loaded()));
            for (int i = 0; i < 520; i++) {
                assertTrue(FeaturePersistenceTest.await(ledger.reserve("grave-" + i, recipient)));
                assertTrue(FeaturePersistenceTest.await(ledger.paid("grave-" + i, recipient)));
                assertTrue(state.snapshot().isEmpty());
            }
            // The first identity is outside the bounded recent cache. Disk must still deny it.
            assertFalse(FeaturePersistenceTest.await(ledger.reserve("grave-0", recipient)));
            assertTrue(FeaturePersistenceTest.await(ledger.isArchived("grave-0")));
            assertTrue(ledger.isBlocked("grave-0"));
            assertTrue(FeaturePersistenceTest.await(ledger.reserve("new-grave", recipient)));
        }
        try (FeatureIO io = new FeatureIO()) {
            var ledger = new GraveClaimLedger(new DurableFeatureState(io, disk, 2), directory.resolve("archive"));
            assertTrue(FeaturePersistenceTest.await(ledger.loaded()));
            assertFalse(FeaturePersistenceTest.await(ledger.reserve("grave-0", recipient)));
            assertTrue(FeaturePersistenceTest.await(ledger.isArchived("grave-0")));
            assertFalse(FeaturePersistenceTest.await(ledger.reserve("new-grave", recipient)));
            assertTrue(FeaturePersistenceTest.await(ledger.reserve("after-restart", recipient)));
        }
    }
    @Test void migratePaidButNeverRetireUncertainReservations() throws Exception {
        var disk = new FeaturePersistenceTest.Disk();
        disk.values = Map.of("old-paid", "PAID:" + recipient, "uncertain", "RESERVED:" + recipient);
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, disk, 2);
            var ledger = new GraveClaimLedger(state, directory.resolve("archive"));
            assertTrue(FeaturePersistenceTest.await(ledger.loaded()));
            assertEquals(Set.of("uncertain"), state.snapshot().keySet());
            assertFalse(FeaturePersistenceTest.await(ledger.reserve("old-paid", recipient)));
            assertFalse(FeaturePersistenceTest.await(ledger.reserve("uncertain", recipient)));
            assertTrue(FeaturePersistenceTest.await(ledger.reserve("new", recipient)));
        }
    }
    @Test void failedTombstonePublicationRetainsPayoutReservationAcrossRestart() throws Exception {
        var disk = new FeaturePersistenceTest.Disk();
        Path archive = directory.resolve("archive");
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, disk, 2); var ledger = new GraveClaimLedger(state, archive);
            assertTrue(FeaturePersistenceTest.await(ledger.loaded()));
            assertTrue(FeaturePersistenceTest.await(ledger.reserve("grave", recipient)));
            Files.writeString(archive, "not a directory");
            assertThrows(Exception.class, () -> FeaturePersistenceTest.await(ledger.paid("grave", recipient)));
            assertTrue(state.snapshot().get("grave").startsWith("RESERVED:"));
            assertTrue(ledger.isBlocked("grave"));
        }
        Files.delete(archive);
        try (FeatureIO io = new FeatureIO()) {
            var ledger = new GraveClaimLedger(new DurableFeatureState(io, disk, 2), archive);
            assertTrue(FeaturePersistenceTest.await(ledger.loaded()));
            assertFalse(FeaturePersistenceTest.await(ledger.reserve("grave", recipient)));
        }
    }
    @Test void acknowledgedArchiveSurvivesActiveJournalCompletionFailure() throws Exception {
        var disk = new FeaturePersistenceTest.Disk(); Path archive = directory.resolve("archive");
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, disk, 2); var ledger = new GraveClaimLedger(state, archive);
            assertTrue(FeaturePersistenceTest.await(ledger.loaded()));
            assertTrue(FeaturePersistenceTest.await(ledger.reserve("grave", recipient)));
            disk.fail = true;
            assertFalse(FeaturePersistenceTest.await(ledger.paid("grave", recipient)));
            assertTrue(ledger.isBlocked("grave"));
            try (var files = Files.list(archive)) { assertEquals(1, files.count()); }
        }
        disk.fail = false;
        try (FeatureIO io = new FeatureIO()) {
            var ledger = new GraveClaimLedger(new DurableFeatureState(io, disk, 2), archive);
            assertTrue(FeaturePersistenceTest.await(ledger.loaded()));
            assertFalse(FeaturePersistenceTest.await(ledger.reserve("grave", recipient)));
        }
    }
}
