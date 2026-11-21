package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real YAML/file force/atomic replacement, with only directory-handle capabilities injected. */
class DurableFeaturePublicationTest {
    @TempDir Path directory;
    private static final String GRAVE = FeaturePersistenceTest.GRAVE;
    private static final UUID RECIPIENT = FeaturePersistenceTest.HELPER;

    @Test void windowsUnsupportedDirectoryOpenAllowsStartupPublicationAndRestart() throws Exception {
        Path file = directory.resolve("claims.yml"), archive = directory.resolve("archive");
        var opens = new AtomicInteger();
        DurableFeatureState.Disk disk = DurableFeatureState.yamlDisk(file, 8192, true, parent -> {
            assertEquals("gsp-feature-io", Thread.currentThread().getName());
            assertEquals(directory.toAbsolutePath(), parent);
            assertTrue(Files.isRegularFile(file)); // The actual successor was atomically published first.
            opens.incrementAndGet(); throw new AccessDeniedException(parent.toString());
        });
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, disk, 8192);
            var ledger = new GraveClaimLedger(state, archive);
            assertTrue(FeaturePersistenceTest.await(ledger.loaded())); assertTrue(state.isReady());
            assertEquals(GraveClaimLedger.Restoration.AVAILABLE, FeaturePersistenceTest.await(ledger.restoration(GRAVE)));
            assertTrue(FeaturePersistenceTest.await(ledger.reserve(GRAVE, RECIPIENT)));
            assertTrue(FeaturePersistenceTest.await(ledger.paid(GRAVE, RECIPIENT)));
            assertTrue(ledger.isBlocked(GRAVE));
        }
        try (FeatureIO io = new FeatureIO()) {
            var ledger = new GraveClaimLedger(new DurableFeatureState(io, disk, 8192), archive);
            assertTrue(FeaturePersistenceTest.await(ledger.loaded()));
            assertEquals(GraveClaimLedger.Restoration.PAID, FeaturePersistenceTest.await(ledger.restoration(GRAVE)));
            assertFalse(FeaturePersistenceTest.await(ledger.reserve(GRAVE, RECIPIENT)));
        }
        assertTrue(opens.get() >= 4);
        try (var paths = Files.list(directory)) { assertFalse(paths.anyMatch(path -> path.toString().endsWith(".tmp"))); }
    }

    @Test void posixAccessDeniedAtDirectoryOpenLeavesStartupUnavailable() throws Exception {
        assertStartupFails(false, parent -> { throw new AccessDeniedException(parent.toString()); });
    }

    @Test void windowsGenericOpenErrorIsStillFatal() throws Exception {
        assertStartupFails(true, parent -> { throw new IOException("disk I/O failure"); });
    }

    @Test void windowsAccessDeniedForAnotherPathIsStillFatal() throws Exception {
        assertStartupFails(true, parent -> { throw new AccessDeniedException(parent.resolve("other").toString()); });
    }

    private void assertStartupFails(boolean windows, DurableFeatureState.DirectoryChannel channels) throws Exception {
        Path file = directory.resolve("claims.yml");
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, DurableFeatureState.yamlDisk(file, 8192, windows, channels), 8192);
            var ledger = new GraveClaimLedger(state, directory.resolve("archive"));
            assertFalse(FeaturePersistenceTest.await(ledger.loaded())); assertFalse(state.isReady());
            assertTrue(Files.exists(file)); assertEquals(Map.of(), state.snapshot());
            assertEquals(GraveClaimLedger.Restoration.UNAVAILABLE, FeaturePersistenceTest.await(ledger.restoration(GRAVE)));
            assertFalse(FeaturePersistenceTest.await(ledger.reserve(GRAVE, RECIPIENT)));
            assertTrue(ledger.isBlocked(GRAVE));
        }
    }

    @Test void windowsForceAccessDeniedAfterReplacementCannotAcknowledgeOrAllowRetry() throws Exception {
        Path file = directory.resolve("claims.yml"); var fail = new AtomicBoolean();
        FileChannel channel = mock(FileChannel.class);
        doAnswer(call -> { if (fail.get()) throw new AccessDeniedException(directory.toString()); return null; })
                .when(channel).force(true);
        var disk = DurableFeatureState.yamlDisk(file, 8192, true, parent -> channel);
        try (FeatureIO io = new FeatureIO()) {
            var state = new DurableFeatureState(io, disk, 8192);
            var ledger = new GraveClaimLedger(state, directory.resolve("archive"));
            assertTrue(FeaturePersistenceTest.await(ledger.loaded())); fail.set(true);
            assertFalse(FeaturePersistenceTest.await(ledger.reserve(GRAVE, RECIPIENT)));
            assertFalse(state.isReady()); assertEquals(Map.of(), state.snapshot()); assertTrue(ledger.isBlocked(GRAVE));
            assertFalse(FeaturePersistenceTest.await(ledger.reserve(GRAVE, RECIPIENT)));
            assertEquals("RESERVED:" + RECIPIENT, disk.load().get(GRAVE)); // Replacement can have committed despite failure.
        }
        verify(channel, times(2)).force(true); verify(channel, times(2)).close();
        // A strict real-file read on restart must reconcile the successor, never replay payout.
        try (FeatureIO io = new FeatureIO()) {
            var ledger = new GraveClaimLedger(new DurableFeatureState(io, file, 8192), directory.resolve("archive"));
            assertTrue(FeaturePersistenceTest.await(ledger.loaded()));
            assertEquals(GraveClaimLedger.Restoration.RESERVED, FeaturePersistenceTest.await(ledger.restoration(GRAVE)));
            assertFalse(FeaturePersistenceTest.await(ledger.reserve(GRAVE, RECIPIENT)));
        }
    }
}
