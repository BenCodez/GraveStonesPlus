package com.bencodez.gravestonesplus.tests.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.bencodez.gravestonesplus.graves.FeatureIO;
import com.bencodez.gravestonesplus.storage.*;

/** Real journal file forcing, atomic moves, replay and retirement with injected directory capability only. */
class SqlJournalDirectoryTest {
    @TempDir Path directory;
    interface Open { FileChannel open(Path path) throws IOException; }
    private SqlRecoveryJournal journal(FeatureIO io, GraveStorageBackend backend, Path path,
            boolean windows, Open open) throws Exception {
        Class<?> seam = Class.forName(SqlRecoveryJournal.class.getName() + "$DirectoryChannel");
        Object channels = Proxy.newProxyInstance(seam.getClassLoader(), new Class<?>[] { seam },
                (proxy, method, args) -> open.open((Path) args[0]));
        var constructor = SqlRecoveryJournal.class.getDeclaredConstructor(FeatureIO.class, GraveStorageBackend.class,
                Path.class, Consumer.class, int.class, long.class, boolean.class, seam);
        constructor.setAccessible(true);
        return constructor.newInstance(io, backend, path, (Consumer<String>) ignored -> {}, 256, 64L * 1_048_576, windows, channels);
    }
    private static <T> T await(CompletableFuture<T> result) throws Exception { return result.get(5, TimeUnit.SECONDS); }
    private static FileChannel denied(Path path) throws IOException { throw new AccessDeniedException(path.toString()); }

    @Test void windowsUnsupportedOpenCoversNewAncestorsPublicationRetirementAndReplay() throws Exception {
        Path path = directory.resolve("new/child/sql"); var opens = new java.util.concurrent.CopyOnWriteArrayList<Path>();
        Open unsupported = parent -> {
            assertEquals("gsp-feature-io", Thread.currentThread().getName());
            assertTrue(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)); opens.add(parent);
            return denied(parent);
        };
        var record = SqlRecoveryJournalTest.prepared();
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class);
            doThrow(new IllegalStateException("synthetic outage")).when(backend).upsertGrave(any(), anyBoolean());
            var journal = journal(io, backend, path, true, unsupported);
            assertTrue(await(journal.loaded())); assertTrue(journal.isReady());
            var slot = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, record, false); assertNotNull(slot);
            slot.commit(); assertEquals(SqlRecoveryJournal.Disposition.DURABLE, await(slot.durable()));
            await(io.barrier());
            try (var files = Files.list(path)) { assertEquals(1, files.count()); }
            journal.close();
        }
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend, path, true, unsupported);
            assertTrue(await(journal.loaded())); await(io.barrier());
            verify(backend).upsertGrave(any(), eq(false));
            try (var files = Files.list(path)) { assertEquals(0, files.count()); }
            assertTrue(journal.isReady()); journal.close();
        }
        assertTrue(opens.contains(path.toAbsolutePath().getParent()));
        assertTrue(opens.stream().filter(path::equals).count() >= 4);
    }
    private void startupFails(boolean windows, Open open) throws Exception {
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend, directory, windows, open);
            assertFalse(await(journal.loaded())); assertFalse(journal.isReady());
            assertNull(journal.reserve(SqlRecoveryJournal.Kind.FLAG, SqlRecoveryJournalTest.prepared(), true));
            verifyNoInteractions(backend); journal.close();
        }
    }
    @Test void nonWindowsProviderDenialFailsClosed() throws Exception { startupFails(false, SqlJournalDirectoryTest::denied); }
    @Test void genericWindowsIoFailureFailsClosed() throws Exception {
        startupFails(true, path -> { throw new IOException("synthetic I/O failure"); });
    }
    @Test void anotherPathDenialFailsClosed() throws Exception {
        startupFails(true, path -> { throw new AccessDeniedException(path.resolve("other").toString()); });
    }
    @Test void multiPathDenialFailsClosed() throws Exception {
        startupFails(true, path -> { throw new AccessDeniedException(path.toString(), "other", null); });
    }
    @Test void realForceFailureIsNeverAnUnsupportedOpen() throws Exception {
        FileChannel channel = mock(FileChannel.class);
        doThrow(new AccessDeniedException(directory.toString())).when(channel).force(true);
        startupFails(true, path -> channel); verify(channel).close();
    }
    @Test void realCloseFailureIsNeverAnUnsupportedOpen() throws Exception {
        FileChannel channel = mock(FileChannel.class);
        doThrow(new AccessDeniedException(directory.toString())).when(channel).close();
        startupFails(true, path -> channel); verify(channel).force(true);
    }
    @Test void missingDirectoryCannotMasqueradeAsUnsupportedOpen() throws Exception {
        startupFails(true, path -> {
            Files.delete(path); return denied(path);
        });
    }
    @Test void existingSymbolicLinkParentRemainsCompatibleAndIsPinnedAcrossReplay() throws Exception {
        Path target = Files.createDirectory(directory.resolve("target"));
        Path link = Files.createSymbolicLink(directory.resolve("alias"), target);
        Path path = link.resolve("sql"); var record = SqlRecoveryJournalTest.prepared();
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class);
            doThrow(new IllegalStateException("synthetic outage")).when(backend).upsertGrave(any(), anyBoolean());
            var journal = journal(io, backend, path, false,
                    parent -> FileChannel.open(parent, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            assertTrue(await(journal.loaded()));
            // Changing the alias after startup must not redirect accepted journal writes.
            Files.delete(link); Files.createSymbolicLink(link, Files.createDirectory(directory.resolve("other")));
            var slot = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, record, false);
            slot.commit(); assertEquals(SqlRecoveryJournal.Disposition.DURABLE, await(slot.durable()));
            await(io.barrier());
            try (var files = Files.list(target.resolve("sql"))) { assertEquals(1, files.count()); }
            assertFalse(Files.exists(path)); journal.close();
        }
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class);
            var journal = journal(io, backend, target.resolve("sql"), false,
                    parent -> FileChannel.open(parent, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
            assertTrue(await(journal.loaded())); await(io.barrier());
            verify(backend).upsertGrave(any(), eq(false)); journal.close();
        }
    }
    @Test void linkedJournalItselfIsStillRejectedWithoutReadingItsRecords() throws Exception {
        Path target = Files.createDirectory(directory.resolve("target"));
        Path link = Files.createSymbolicLink(directory.resolve("sql"), target);
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class);
            var journal = journal(io, backend, link, true, path -> fail("Opened linked journal"));
            assertFalse(await(journal.loaded())); verifyNoInteractions(backend); journal.close();
        }
    }
    @Test void forceFailureAfterAtomicPublicationRetainsReplayEvidenceAndDoesNotAckSql() throws Exception {
        var fail = new AtomicBoolean(); var attempts = new AtomicInteger();
        Open channels = path -> {
            if (fail.get()) {
                FileChannel channel = mock(FileChannel.class);
                doThrow(new AccessDeniedException(path.toString())).when(channel).force(true); return channel;
            }
            return denied(path);
        };
        var record = SqlRecoveryJournalTest.prepared();
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class); var journal = journal(io, backend, directory, true, channels);
            assertTrue(await(journal.loaded())); fail.set(true);
            var slot = journal.reserve(SqlRecoveryJournal.Kind.UPSERT, record, false);
            assertThrows(ExecutionException.class, () -> await(slot.commit()));
            assertEquals(SqlRecoveryJournal.Disposition.HELD, await(slot.durable()));
            verifyNoInteractions(backend);
            try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
            journal.close();
        }
        try (var io = new FeatureIO(8)) {
            var backend = mock(GraveStorageBackend.class);
            doAnswer(call -> { attempts.incrementAndGet(); return null; }).when(backend).upsertGrave(any(), anyBoolean());
            var journal = journal(io, backend, directory, true, SqlJournalDirectoryTest::denied);
            assertTrue(await(journal.loaded())); await(io.barrier()); assertEquals(1, attempts.get()); journal.close();
        }
    }
}
