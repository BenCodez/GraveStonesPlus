package com.bencodez.gravestonesplus.tests.storage;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import com.bencodez.gravestonesplus.graves.FeatureIO;
import com.bencodez.gravestonesplus.storage.*;

/** Test-only installation of a real isolated journal into the manager's backend fixture. */
public final class SqlJournalHarness implements AutoCloseable {
    public final Path directory;
    public final SqlRecoveryJournal journal;
    private final FeatureIO io;
    private final GraveStorageManager manager;
    private final boolean cleanup;
    public SqlJournalHarness(FeatureIO io, GraveStorageBackend backend, GraveStorageManager manager) throws Exception {
        this(io, backend, manager, Files.createTempDirectory("gsp-sql-journal-test-"), true);
    }
    public SqlJournalHarness(FeatureIO io, GraveStorageBackend backend, GraveStorageManager manager, Path directory) throws Exception {
        this(io, backend, manager, directory, false);
    }
    private SqlJournalHarness(FeatureIO io, GraveStorageBackend backend, GraveStorageManager manager, Path directory, boolean cleanup) throws Exception {
        this.io = io; this.manager = manager; this.directory = directory; this.cleanup = cleanup;
        journal = new SqlRecoveryJournal(io, backend, directory, ignored -> {});
        var field = GraveStorageManager.class.getDeclaredField("sqlJournal"); field.setAccessible(true); field.set(manager, journal);
        if (!journal.loaded().get(5, TimeUnit.SECONDS)) throw new AssertionError("Synthetic journal unavailable");
    }
    public long records() throws Exception {
        try (var paths = Files.list(directory)) { return paths.filter(path -> path.getFileName().toString().endsWith(".sql")).count(); }
    }
    public static String encoded(GravesConfig record) throws Exception {
        var method = GravesConfig.class.getDeclaredMethod("encodedItemsForStorage"); method.setAccessible(true);
        return (String) method.invoke(record);
    }
    @Override public void close() throws Exception {
        manager.close(); journal.close(); io.close();
        if (!cleanup) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
