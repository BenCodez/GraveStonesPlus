package com.bencodez.gravestonesplus.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.bencodez.gravestonesplus.graves.FeatureIO;

/** Bounded, ordered SQL obligations. Only the feature worker touches journal files or JDBC.
 * This storage-only component cannot atomically commit a Minecraft inventory/world transition.
 * Callers must supply owner-side identity/reconciliation before committing an obligation. Once
 * committed it may replay even if observation fails; HELD never permits rollback or a second payout.
 * Future continuations run on a bounded notification strand and must hand off without blocking.
 */
public final class SqlRecoveryJournal {
    /** HELD may replay after repair/restart: the caller must retain ownership and must not restore/drop rewards.
     * CANCELLED is possible only before commit and cannot replay. DURABLE is not an SQL acknowledgment. */
    public enum Disposition { DURABLE, HELD, CANCELLED }
    public enum Kind { UPSERT, UPDATE, FLAG, DISPLAY, RECREATE, BULK, REMOVE }
    private enum Phase { RESERVED, READY, DURABLE }
    private static final int VERSION = 1;
    private static final int MAX_RECORD = 1_048_576;
    private static final int RECREATION_BYTES = 177;
    private static final int MAX_FILE = 64 * 1_048_576;
    private static final int MAX_ENTRIES = 256;
    private static final long MAX_BYTES = 64L * 1_048_576;
    private final FeatureIO io;
    private final GraveStorageBackend backend;
    private final Path directory;
    private final Consumer<String> error;
    private final int entryLimit;
    private final long byteLimit;
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>();
    private final CompletableFuture<Boolean> loaded = new CompletableFuture<>();
    private final ThreadPoolExecutor notifications;
    private int outstanding, evidenceEntries;
    private long evidenceBytes;
    private long next, bytes, cursor;
    private final Set<String> blockedCycle = new HashSet<>();
    private boolean healthy, closing, draining, initialized, retryRequested, fatal, loadComplete, bulkContinuing;
    private boolean fatalSettled;
    private String fatalMessage;
    private static final class Entry {
        final long sequence;
        final Kind kind;
        GravesConfig grave;
        final boolean broken;
        byte[] payload;
        final long charge;
        final boolean late;
        final String identity;
        final List<Entry> rows;
        int nextRow;
        FeatureIO.Admission bulkTicket;
        final CompletableFuture<Disposition> durable = new CompletableFuture<>();
        boolean reported, durableNotified;
        final CompletableFuture<Boolean> completion = new CompletableFuture<>();
        Phase phase;
        Entry(long sequence, Kind kind, GravesConfig grave, boolean broken, byte[] payload, Phase phase) {
            this.sequence = sequence; this.kind = kind; this.grave = grave; this.broken = broken;
            this.payload = payload; this.phase = phase; this.rows = List.of();
            this.charge = payload == null ? 0 : payload.length; this.late = false;
            identity = identity(grave);
        }
            Entry(long sequence, byte[] payload, List<Entry> rows, Phase phase) {
            this.sequence = sequence; this.kind = Kind.BULK; this.grave = null; this.broken = false;
            this.payload = payload; this.rows = List.copyOf(rows); this.phase = phase; this.identity = "bulk:" + sequence;
            this.charge = payload.length; this.late = false;
        }
        Entry(long sequence, String identity) {
            this.sequence = sequence; this.kind = Kind.RECREATE; this.grave = null; this.broken = false;
            this.payload = null; this.identity = identity; this.rows = List.of(); this.phase = Phase.RESERVED;
            this.charge = RECREATION_BYTES; this.late = true;
        }
    }
    private static String identity(GravesConfig grave) {
        return grave.getUuid() + ":" + grave.getWorldId() + ":" + grave.getTime() + ":"
                + grave.getLocation().getBlockX() + ":" + grave.getLocation().getBlockY() + ":" + grave.getLocation().getBlockZ();
    }
    /** Each row is already prepared on the caller owner; bulk uses one admission and a byte-bounded packet. */
    public record BulkRow(GravesConfig grave, boolean broken) { }
    public SqlRecoveryJournal(FeatureIO io, GraveStorageBackend backend, Path directory, Consumer<String> error) {
        this(io, backend, directory, error, MAX_ENTRIES, MAX_BYTES);
    }
    SqlRecoveryJournal(FeatureIO io, GraveStorageBackend backend, Path directory, Consumer<String> error,
            int entryLimit, long byteLimit) {
        this.io = io; this.backend = backend; this.directory = Objects.requireNonNull(directory); this.error = error;
        this.entryLimit = entryLimit; this.byteLimit = byteLimit;
        notifications = new ThreadPoolExecutor(1, 1, 10L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(entryLimit * 3 + 2), runnable -> {
                    Thread thread = new Thread(runnable, "gsp-sql-notifications"); thread.setDaemon(true); return thread;
                });
        notifications.allowCoreThreadTimeOut(true);
        io.submit(() -> { load(); return true; }).whenComplete((value, failure) -> {
            synchronized (this) { healthy = failure == null; initialized = failure == null; loadComplete = true; }
            wake();
            notifications.execute(() -> {
                try { loaded.complete(failure == null); }
                finally { if (failure != null) report("SQL recovery journal unavailable; new SQL transitions are blocked."); }
            });
        });
    }
    public CompletableFuture<Boolean> loaded() { return loaded.copy(); }
    public synchronized boolean isReady() { return healthy && !closing; }
    synchronized boolean isLoadComplete() { return loadComplete; }
    synchronized boolean isStorageReconciled() { return initialized && healthy && entries.isEmpty(); }
    synchronized boolean hasUncommittedReservations() {
        return entries.values().stream().anyMatch(entry -> entry.phase == Phase.RESERVED);
    }
    /** Backend teardown is terminal even if durable outage records remain for the next instance. */
    void retireAfterDrain() {
        if (!io.isOwnerThread()) throw new IllegalStateException("SQL retirement requires the feature owner");
        settleFatal("SQL backend retired; remaining durable intents require restart reconciliation.");
    }
    /** Record must already be detached and encoded on its Bukkit/Folia owner. */
    public synchronized Reservation reserve(Kind kind, GravesConfig record, boolean broken) {
        if (kind == Kind.BULK || !canReserve()) return null;
        FeatureIO.Admission admission = io.admit();
        if (admission == null) return null;
        boolean transferred = false;
        try {
            byte[] payload = encode(next + 1, kind, record, broken);
            if (payload.length > byteLimit - bytes - evidenceBytes) return null;
            Reservation result = install(payload, admission); transferred = true; return result;
        } catch (IOException | RuntimeException invalid) { return null; }
        finally { if (!transferred) admission.close(); }
    }
    /** Reserve marker metadata capacity before native UUIDs exist. No provisional wire can publish. */
    public synchronized Reservation reserveRecreation(GravesConfig record) {
        if (!canReserve() || RECREATION_BYTES > byteLimit - bytes - evidenceBytes) return null;
        FeatureIO.Admission admission = io.admit();
        if (admission == null) return null;
        boolean transferred = false;
        try {
            if (record == null || record.getUuid() == null || record.getWorldId() == null || record.getLocation() == null) return null;
            Entry entry = new Entry(next + 1, identity(record));
            Reservation result = new Reservation(entry, admission);
            entries.put(entry.sequence, entry); next = entry.sequence; bytes += entry.charge; outstanding++;
            transferred = true; return result;
        } catch (RuntimeException invalid) { return null; }
        finally { if (!transferred) admission.close(); }
    }
    /** Captures a caller-owned snapshot into one byte-bounded durable obligation.
     * SQL advances in sixteen-row windows. Unfinished rows retry in order; a restart
     * may replay the idempotent upsert prefix before any later SQL obligation.
     */
    public synchronized Reservation reserveBulk(java.util.Iterator<BulkRow> rows) {
        if (!canReserve()) return null;
        FeatureIO.Admission admission = io.admit();
        if (admission == null) return null;
        boolean transferred = false;
        try {
            long available = Math.min(MAX_FILE, byteLimit - bytes - evidenceBytes);
            if (available < 49) return null;
            byte[] payload = encodeBulk(next + 1, rows, (int) available);
            Reservation result = install(payload, admission); transferred = true; return result;
        } catch (IOException | RuntimeException invalid) { return null; }
        finally { if (!transferred) admission.close(); }
    }
    private boolean canReserve() { return healthy && !closing && outstanding + evidenceEntries < entryLimit && next != Long.MAX_VALUE; }
    private Reservation install(byte[] payload, FeatureIO.Admission admission) throws IOException {
        Entry entry = decode(++next, payload); entry.phase = Phase.RESERVED;
        Reservation result = new Reservation(entry, admission);
        entries.put(entry.sequence, entry); bytes += entry.charge; outstanding++;
        return result;
    }
    public final class Reservation implements AutoCloseable {
        private final Entry entry;
        private FeatureIO.Admission admission;
        private Reservation(Entry entry, FeatureIO.Admission admission) { this.entry = entry; this.admission = admission; }
        /** Acknowledges the order-safe journal publication, independently of SQL completion. */
        public CompletableFuture<Disposition> durable() { return entry.durable.copy(); }
        /** Finalize once on the marker owner. Invalid preparation cancels only an unused late slot. */
        public boolean prepareRecreation(GravesConfig record) {
            FeatureIO.Admission cancelled = null;
            try {
                synchronized (SqlRecoveryJournal.this) {
                    if (admission == null || !entry.late || entry.payload != null || entry.phase != Phase.RESERVED) return false;
                    boolean accepted = false;
                    try {
                        if (fatal) return false;
                        if (record == null || record.isDestroyed() || record.getDestroyedTime() != 0 || record.getExp() != 0
                                || !Objects.toString(record.getPlayerName(), "").isEmpty()
                                || !Objects.toString(record.getDeathMessage(), "").isEmpty()) return false;
                        String type = record.getGraveDisplayType();
                        if (!Set.of("CHEST", "PLAYER_HEAD", "DISPLAY_ENTITY").contains(type)) return false;
                        // Encode a bounded scalar capture, never re-read live strings or inventories.
                        var location = record.getLocation();
                        GravesConfig detached = new GravesConfig(record.getUuid(), "",
                                new org.bukkit.Location(null, location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                                new HashMap<>(), 0, "", record.getTime(), false, 0,
                                record.getDisplayUUID(), record.getInteractUUID(), type);
                        detached.setWorldId(record.getWorldId());
                        byte[] payload = encode(entry.sequence, Kind.RECREATE, detached, false);
                        if (payload.length > entry.charge) return false;
                        Entry prepared = decode(entry.sequence, payload);
                        if (!entry.identity.equals(prepared.identity)) return false;
                        entry.grave = prepared.grave; entry.payload = payload;
                        accepted = true; return true;
                    } catch (IOException | RuntimeException invalid) { return false; }
                    finally {
                        if (!accepted) {
                            cancelled = admission; admission = null;
                            entries.remove(entry.sequence, entry);
                        }
                    }
                }
            } finally { if (cancelled != null) cancel(entry, cancelled); }
        }
        /** Consumes the reserved slot; the returned future acknowledges SQL, never merely RAM admission. */
        public CompletableFuture<Boolean> commit() {
            FeatureIO.Admission ticket;
            boolean held, cancelled;
            synchronized (SqlRecoveryJournal.this) {
                if (admission == null) return entry.completion.copy();
                ticket = admission; admission = null; cancelled = entry.payload == null; held = fatal;
                if (cancelled) entries.remove(entry.sequence, entry);
                else {
                    entry.phase = Phase.READY;
                    if (!held && entry.kind == Kind.BULK) entry.bulkTicket = ticket;
                }
            }
            if (cancelled) { cancel(entry, ticket); return entry.completion.copy(); }
            if (held) {
                try { ticket.close(); }
                catch (RuntimeException | Error failure) { report("Held SQL admission wake failed after release."); }
                finally { fail(entry, "SQL journal is terminally held; accepted intent requires owner reconciliation.", true); }
                return entry.completion.copy();
            }
            // Request service while the admission still fences final shutdown.
            // A fast worker may finish submit before this caller resumes.
            try {
                wake();
                if (entry.kind != Kind.BULK) ticket.submit(() -> true).whenComplete((value, failure) -> {
                    if (failure != null) fail(entry, "SQL recovery worker rejected an accepted transition.");
                });
            } catch (RuntimeException | Error failure) {
                try { ticket.close(); }
                catch (RuntimeException | Error releaseFailure) { report("SQL dispatch admission wake failed after release."); }
                finally { settleFatal("SQL recovery dispatch failed after accepting a transition."); }
            }
            return entry.completion.copy();
        }
        boolean cancelIfUnused() {
            FeatureIO.Admission ticket;
            synchronized (SqlRecoveryJournal.this) {
                ticket = admission; admission = null;
                if (ticket == null) return false;
                entries.remove(entry.sequence);
            }
            cancel(entry, ticket); return true;
        }
        @Override public void close() { cancelIfUnused(); }
    }
    private void cancel(Entry entry, FeatureIO.Admission ticket) {
        // Removing a RESERVED barrier can expose accepted successors during shutdown.
        try { wake(); }
        catch (RuntimeException | Error failure) {
            settleFatal("SQL cancellation drain unavailable; accepted successors require reconciliation.");
        } finally {
            try { ticket.close(); }
            catch (RuntimeException | Error failure) {
                settleFatal("SQL cancellation wake unavailable after releasing its admission.");
            } finally {
                notifications.execute(() -> {
                    try { entry.durable.complete(Disposition.CANCELLED); entry.completion.complete(false); }
                    finally { release(entry); }
                });
            }
        }
    }
    /** Explicit retry signal; never blocks a Bukkit/region owner and never loops on a failed backend. */
    public void wake() {
        synchronized (this) { if (fatal) return; retryRequested = true; }
        continueDrain();
    }
    private void continueDrain() { synchronized (this) { if (fatal) return; } io.requestDrain(this::drain); }
    public void close() {
        synchronized (this) {
            closing = true;
            if (outstanding == 0 && loaded.isDone()) notifications.shutdown();
        }
        wake();
    }
    /** Called on FeatureIO after accepted work, including its final shutdown drain. */
    public void drain() {
        if (!io.isOwnerThread()) throw new IllegalStateException("SQL recovery requires the feature owner");
        synchronized (this) { if (fatal || draining || !initialized || (cursor == 0 && !retryRequested && !bulkContinuing)) return; draining = true; }
        try {
            List<Entry> prefix = new ArrayList<>(), publication = new ArrayList<>();
            synchronized (this) {
                // Continuations must retain pass progress through a held predecessor to the bulk barrier.
                if (cursor == 0 && !bulkContinuing) { blockedCycle.clear(); retryRequested = false; }
                int limit = closing ? entryLimit : 16;
                boolean bulkBarrier = false;
                for (Entry entry : entries.values()) {
                    // File acceptance may proceed past a SQL-held bulk, but never past a missing reservation.
                    if (entry.phase == Phase.RESERVED) break;
                    publication.add(entry);
                    if (entry.sequence <= cursor || prefix.size() >= limit || bulkBarrier) continue;
                    if (entry.kind == Kind.BULK && !prefix.isEmpty()) { bulkBarrier = true; continue; }
                    prefix.add(entry);
                    if (entry.kind == Kind.BULK) bulkBarrier = true;
                }
            }
            // Persist the entire bounded order-safe prefix before JDBC or user notifications.
            for (Entry entry : publication) {
                if (settleRequestedFatal()) return;
                if (entry.phase == Phase.DURABLE) continue;
                try {
                    publish(entry);
                    synchronized (this) { entry.phase = Phase.DURABLE; }
                } catch (IOException journalFailure) {
                    settleFatal("SQL recovery publication held; replay remains possible.");
                    return;
                }
            }
            if (settleRequestedFatal()) return;
            for (Entry entry : publication) notifyDurable(entry);
            for (Entry entry : prefix) {
                if (settleRequestedFatal()) return;
                if (blockedCycle.contains(entry.identity)) {
                    fail(entry, "Durable SQL predecessor remains unresolved; successor retained without acknowledgment.", false);
                    continue;
                }
                if (entry.kind == Kind.BULK) {
                    boolean prior;
                    synchronized (this) { prior = entries.keySet().stream().anyMatch(sequence -> sequence < entry.sequence); }
                    if (prior) { holdBulkAndSuccessors(entry, "Bulk SQL predecessor remains held; snapshot retained."); pauseBulk(); return; }
                    try {
                        int end = Math.min(entry.rows.size(), entry.nextRow + 16);
                        while (entry.nextRow < end) {
                            if (settleRequestedFatal()) return;
                            apply(entry.rows.get(entry.nextRow)); entry.nextRow++;
                        }
                    } catch (RuntimeException | Error failure) {
                        holdBulkAndSuccessors(entry, "Bulk SQL snapshot retained for ordered recovery."); pauseBulk(); return;
                    }
                    if (entry.nextRow < entry.rows.size()) {
                        synchronized (this) { cursor = entry.sequence - 1; bulkContinuing = true; }
                        continueDrain(); return;
                    }
                }
                try {
                    boolean applied = true;
                    try { if (entry.kind != Kind.BULK) apply(entry); }
                    catch (SqlConditionNotMet miss) { applied = false; }
                    retire(entry); releaseBulkTicket(entry);
                    synchronized (this) { entries.remove(entry.sequence, entry); }
                    boolean acknowledged = applied;
                    notifications.execute(() -> {
                        try { entry.completion.complete(acknowledged); }
                        finally { release(entry); }
                    });
                } catch (IOException journalFailure) {
                    settleFatal("SQL completion journal retirement held; replay remains possible.");
                    return;
                } catch (RuntimeException | Error databaseFailure) {
                    blockedCycle.add(entry.identity);
                    fail(entry, "SQL transition retained for durable recovery; SQL acknowledgment unavailable.", false);
                }
            }
            boolean more = false;
            synchronized (this) {
                if (!prefix.isEmpty()) cursor = prefix.getLast().sequence;
                bulkContinuing = false;
                for (Entry entry : entries.values()) if (entry.sequence > cursor) {
                    more = entry.phase != Phase.RESERVED; break;
                }
                if (!more) { cursor = 0; blockedCycle.clear(); }
            }
            synchronized (this) { more |= retryRequested; }
            if (more) continueDrain();
        } catch (RuntimeException | Error dispatchFailure) {
            settleFatal("SQL recovery continuation failed; accepted successors require reconciliation.");
        } finally {
            synchronized (this) { draining = false; }
            settleRequestedFatal();
        }
    }
    /** Only the drain owner settles an externally requested fault while a pass is active. */
    private boolean settleRequestedFatal() {
        String message;
        synchronized (this) { if (!fatal) return false; message = fatalMessage; }
        settleFatal(message); return true;
    }
    private void holdBulkAndSuccessors(Entry bulk, String message) {
        releaseBulkTicket(bulk);
        List<Entry> held;
        synchronized (this) { held = entries.values().stream().filter(entry -> entry.sequence >= bulk.sequence && entry.phase == Phase.DURABLE).toList(); }
        for (Entry entry : held) { releaseBulkTicket(entry); fail(entry, message, false); }
    }
    private void releaseBulkTicket(Entry entry) {
        FeatureIO.Admission ticket;
        synchronized (this) { ticket = entry.bulkTicket; entry.bulkTicket = null; }
        if (ticket != null) try { ticket.close(); }
        catch (RuntimeException | Error failure) { report("SQL bulk admission wake failed after release; remaining observers still settle."); }
    }
    private void pauseBulk() {
        boolean retry;
        synchronized (this) { cursor = 0; bulkContinuing = false; blockedCycle.clear(); retry = retryRequested; }
        if (retry) continueDrain();
    }
    /** A fatal journal fault owns the whole accepted queue, not just the current bounded pass. */
    private void settleFatal(String message) {
        List<Entry> affected;
        synchronized (this) {
            healthy = false; fatal = true;
            if (fatalMessage == null) fatalMessage = message;
            if (fatalSettled || draining && !io.isOwnerThread()) return;
            fatalSettled = true;
            cursor = 0; bulkContinuing = false; retryRequested = false; blockedCycle.clear();
            affected = entries.values().stream().filter(entry -> entry.phase != Phase.RESERVED).toList();
        }
        for (Entry entry : affected) {
            releaseBulkTicket(entry);
            if (entry.phase == Phase.DURABLE) notifyDurable(entry);
            fail(entry, message, entry.phase != Phase.DURABLE);
        }
    }
    private void notifyDurable(Entry entry) {
        if (!entry.durableNotified) {
            entry.durableNotified = true;
            notifications.execute(() -> entry.durable.complete(Disposition.DURABLE));
        }
    }
    private synchronized void release(Entry entry) {
        outstanding--; bytes -= entry.charge;
        if (closing && outstanding == 0 && loaded.isDone()) notifications.shutdown();
    }
    private void report(String message) {
        try { error.accept(message); } catch (Throwable ignored) { /* Diagnostic callbacks cannot own recovery. */ }
    }
    private void fail(Entry entry, String message) { fail(entry, message, true); }
    private void fail(Entry entry, String message, boolean publication) {
        synchronized (this) { if (entry.reported) return; entry.reported = true; }
        notifications.execute(() -> {
            try {
                if (publication) entry.durable.complete(Disposition.HELD);
                entry.completion.completeExceptionally(new IllegalStateException(message));
            } finally { report(message); }
        });
    }
    private void apply(Entry entry) {
        switch (entry.kind) {
            case UPSERT -> backend.upsertGrave(entry.grave, entry.broken);
            case UPDATE -> backend.updateExistingGrave(entry.grave, entry.broken);
            case REMOVE -> backend.removeExistingGrave(entry.grave);
            case FLAG -> backend.setBroken(entry.grave, entry.broken);
            case DISPLAY -> backend.saveDisplayIdentity(entry.grave);
            case RECREATE -> backend.saveRecreatedGrave(entry.grave);
            case BULK -> throw new IllegalStateException("Bulk SQL requires bounded progress");
        }
    }
    private Path file(long sequence) { return directory.resolve(String.format(java.util.Locale.ROOT, "%019d.sql", sequence)); }
    private void publish(Entry entry) throws IOException {
        Path target = file(entry.sequence), temporary = directory.resolve(target.getFileName() + ".tmp");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Arrays.equals(readBounded(target), entry.payload)) throw new IOException("Conflicting recovery publication");
            // Rename may have succeeded before a failed directory sync. Reconcile identical bytes, do not overwrite.
            forceFile(target); forceDirectory(); return;
        }
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            byte[] existing = readBounded(temporary);
            if (!Arrays.equals(existing, entry.payload)) throw new IOException("Incomplete recovery publication requires reconciliation");
            forceFile(temporary);
        } else {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer value = ByteBuffer.wrap(entry.payload);
                while (value.hasRemaining()) channel.write(value);
                channel.force(true);
            }
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory();
    }
    private void retire(Entry entry) throws IOException { Files.deleteIfExists(file(entry.sequence)); forceDirectory(); }
    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
    }
    private static void forcePath(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
    }
    private void forceDirectory() throws IOException { forcePath(directory); }
    private byte[] readBounded(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 0 || size > Math.min(MAX_FILE, byteLimit)) throw new IOException("Invalid recovery file size");
            ByteBuffer result = ByteBuffer.allocate((int) size);
            while (result.hasRemaining()) if (channel.read(result) < 0) throw new EOFException("Truncated recovery file");
            if (channel.read(ByteBuffer.allocate(1)) != -1) throw new IOException("Recovery file changed during read");
            return result.array();
        }
    }
    private void load() {
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new IOException("Journal directory cannot be a symbolic link");
            // Persist each new directory's entry in its parent before acknowledging a journal record.
            for (Path path = directory.toAbsolutePath(); path != null; path = path.getParent()) forcePath(path);
            List<Path> files = new ArrayList<>(), temporary = new ArrayList<>();
            int count = 0; long total = 0;
            try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory)) {
                for (Path path : listing) {
                    String name = path.getFileName().toString();
                    if (++count > entryLimit * 2 || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IOException("Journal entry limit or type invalid");
                    long size = Files.size(path); if (size > Math.min(MAX_FILE, byteLimit) || size > byteLimit - total) throw new IOException("Journal byte limit exceeded"); total += size;
                    if (name.matches("[0-9]{19}\\.sql")) files.add(path);
                    else if (name.matches("[0-9]{19}\\.sql\\.tmp")) temporary.add(path);
                    else if (name.matches("[0-9]{19}\\.sql\\.tmp\\.incomplete")) {
                        evidenceEntries++; evidenceBytes += size; next = Math.max(next, Long.parseLong(name.substring(0, 19)));
                    } else throw new IOException("Unexpected journal entry");
                }
            }
            temporary.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path path : temporary) {
                long sequence = Long.parseLong(path.getFileName().toString().substring(0, 19));
                byte[] payload = readBounded(path);
                if (!validChecksum(payload)) {
                    // An unrenamed partial file never received durable acceptance. Preserve it for inspection.
                    Files.move(path, path.resolveSibling(path.getFileName() + ".incomplete"), StandardCopyOption.ATOMIC_MOVE);
                    forceDirectory(); evidenceEntries++; evidenceBytes += payload.length; next = Math.max(next, sequence); continue;
                }
                decode(sequence, payload); // Complete but invalid/renamed records are ambiguous: fail closed.
                Path target = file(sequence);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Arrays.equals(payload, readBounded(target))) throw new IOException("Conflicting complete recovery copies");
                    forceFile(target); Files.delete(path);
                } else {
                    forceFile(path); Files.move(path, target, StandardCopyOption.ATOMIC_MOVE); files.add(target);
                }
                forceDirectory();
            }
            if (files.size() + evidenceEntries > entryLimit) throw new IOException("Journal entry limit exceeded");
            files.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path path : files) {
                long sequence = Long.parseLong(path.getFileName().toString().substring(0, 19));
                byte[] payload = readBounded(path); Entry entry = decode(sequence, payload);
                if (payload.length > byteLimit - bytes - evidenceBytes) throw new IOException("Journal byte limit exceeded");
                entries.put(sequence, entry); bytes += payload.length; outstanding++; next = Math.max(next, sequence);
            }
        } catch (IOException | RuntimeException invalid) { throw new IllegalStateException("SQL recovery journal could not be loaded", invalid); }
    }
    private static void text(DataOutputStream output, String value) throws IOException {
        int length = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 128) length++;
            else if (c < 2048) length += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) { length += 4; i++; }
            else length += Character.isSurrogate(c) ? 1 : 3;
            if (length > MAX_RECORD) throw new IOException("SQL recovery string exceeds limit");
        }
        output.writeInt(length); output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static String text(DataInputStream input) throws IOException {
        int size = input.readInt(); if (size < 0 || size > MAX_RECORD || size > input.available()) throw new IOException("Invalid journal string length");
        return new String(input.readNBytes(size), java.nio.charset.StandardCharsets.UTF_8);
    }
    private static void uuid(DataOutputStream output, UUID value) throws IOException { output.writeBoolean(value != null); if (value != null) { output.writeLong(value.getMostSignificantBits()); output.writeLong(value.getLeastSignificantBits()); } }
    private static UUID uuid(DataInputStream input) throws IOException { return input.readBoolean() ? new UUID(input.readLong(), input.readLong()) : null; }
    private static byte[] encodeBulk(long sequence, java.util.Iterator<BulkRow> rows, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bounded(bytes, limit - 32))) {
            output.writeLong(sequence); output.writeInt(VERSION); output.writeByte(Kind.BULK.ordinal()); output.writeInt(0);
            int count = 0;
            while (rows.hasNext()) {
                BulkRow row = rows.next(); byte[] payload = encode(sequence, Kind.UPSERT, row.grave(), row.broken());
                output.writeInt(payload.length); output.write(payload); count++;
            }
            byte[] body = bytes.toByteArray(); ByteBuffer.wrap(body).putInt(13, count);
            byte[] payload = Arrays.copyOf(body, body.length + 32); System.arraycopy(digest(body), 0, payload, body.length, 32); return payload;
        }
    }
    private static OutputStream bounded(ByteArrayOutputStream bytes, int limit) {
        return new OutputStream() {
            @Override public void write(int value) throws IOException {
                if (bytes.size() >= limit) throw new IOException("SQL recovery record exceeds limit"); bytes.write(value);
            }
            @Override public void write(byte[] value, int offset, int length) throws IOException {
                if (length > limit - bytes.size()) throw new IOException("SQL recovery record exceeds limit"); bytes.write(value, offset, length);
            }
        };
    }
    private static byte[] encode(long sequence, Kind kind, GravesConfig grave, boolean broken) throws IOException {
        if (grave.getUuid() == null || grave.getWorldId() == null || grave.getLocation() == null) throw new IOException("Complete SQL identity required");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bounded(bytes, MAX_RECORD - 32))) {
            output.writeLong(sequence); output.writeInt(VERSION); output.writeByte(kind.ordinal()); output.writeBoolean(broken);
            uuid(output, grave.getUuid()); uuid(output, grave.getWorldId()); output.writeLong(grave.getTime());
            output.writeInt(grave.getLocation().getBlockX()); output.writeInt(grave.getLocation().getBlockY()); output.writeInt(grave.getLocation().getBlockZ());
            text(output, Objects.toString(grave.getPlayerName(), "")); text(output, Objects.toString(grave.getDeathMessage(), ""));
            output.writeInt(grave.getExp()); output.writeBoolean(grave.isDestroyed()); output.writeLong(grave.getDestroyedTime());
            uuid(output, grave.getDisplayUUID()); uuid(output, grave.getInteractUUID()); text(output, Objects.toString(grave.getGraveDisplayType(), ""));
            text(output, kind == Kind.UPSERT || kind == Kind.UPDATE || kind == Kind.REMOVE ? grave.encodedItemsForStorage() : "");
        }
        byte[] body = bytes.toByteArray();
        byte[] checksum = digest(body);
        bytes.write(checksum); return bytes.toByteArray();
    }
    private static byte[] digest(byte[] body) {
        try { return java.security.MessageDigest.getInstance("SHA-256").digest(body); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean validChecksum(byte[] payload) {
        if (payload.length < 32) return false;
        byte[] body = Arrays.copyOf(payload, payload.length - 32);
        return java.security.MessageDigest.isEqual(digest(body), Arrays.copyOfRange(payload, body.length, payload.length));
    }
    private static Entry decode(long sequence, byte[] payload) throws IOException {
        if (payload.length < 32) throw new IOException("Truncated recovery entry");
        byte[] body = Arrays.copyOf(payload, payload.length - 32);
        if (!java.security.MessageDigest.isEqual(digest(body), Arrays.copyOfRange(payload, body.length, payload.length)))
            throw new IOException("Recovery entry checksum mismatch");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            if (input.readLong() != sequence) throw new IOException("Recovery sequence mismatch");
            if (input.readInt() != VERSION) throw new IOException("Unsupported SQL recovery version");
            int ordinal = input.readUnsignedByte(); if (ordinal >= Kind.values().length) throw new IOException("Invalid mutation kind");
            Kind kind = Kind.values()[ordinal];
            if (kind == Kind.BULK) {
                int count = input.readInt();
                if (count < 0 || count > input.available() / 4) throw new IOException("Invalid bulk SQL row count");
                List<Entry> rows = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    int size = input.readInt();
                    if (size < 13 || size > MAX_RECORD || size > input.available()) throw new IOException("Invalid bulk SQL row size");
                    byte[] row = input.readNBytes(size);
                    if (row[12] != Kind.UPSERT.ordinal()) throw new IOException("Bulk SQL permits only full upserts");
                    Entry decoded = decode(sequence, row);
                    rows.add(new Entry(sequence, decoded.kind, decoded.grave, decoded.broken, null, Phase.DURABLE));
                }
                if (input.available() != 0) throw new IOException("Trailing bulk SQL bytes");
                return new Entry(sequence, payload, rows, Phase.DURABLE);
            }
            if (payload.length > MAX_RECORD) throw new IOException("SQL recovery record exceeds limit");
            boolean broken = input.readBoolean();
            UUID player = uuid(input), world = uuid(input); long time = input.readLong();
            var location = new org.bukkit.Location(null, input.readInt(), input.readInt(), input.readInt());
            String name = text(input), death = text(input); int exp = input.readInt(); boolean destroyed = input.readBoolean(); long destroyedTime = input.readLong();
            UUID display = uuid(input), interact = uuid(input); String type = text(input), items = text(input);
            if (player == null || world == null || input.available() != 0) throw new IOException("Invalid recovery identity or trailing bytes");
            GravesConfig grave = new GravesConfig(player, name, location, new HashMap<>(), exp, death, time, destroyed, destroyedTime, display, interact, type);
            grave.setWorldId(world);
            if (kind == Kind.RECREATE) grave.setGraveDisplayType(type);
            grave.acceptPreparedSqlItems(items);
            return new Entry(sequence, kind, grave, broken, payload, Phase.DURABLE);
        }
    }
}
