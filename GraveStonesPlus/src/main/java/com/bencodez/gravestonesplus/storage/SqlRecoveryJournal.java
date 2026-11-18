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
    public enum Kind { UPSERT, UPDATE, FLAG, DISPLAY, RECREATE }
    private enum Phase { RESERVED, READY, DURABLE }
    private static final int VERSION = 1;
    private static final int MAX_RECORD = 1_048_576;
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
    private boolean healthy, closing, draining, initialized, retryRequested;
    private static final class Entry {
        final long sequence;
        final Kind kind;
        final GravesConfig grave;
        final boolean broken;
        final byte[] payload;
        final String identity;
        final CompletableFuture<Disposition> durable = new CompletableFuture<>();
        boolean reported, durableNotified;
        final CompletableFuture<Boolean> completion = new CompletableFuture<>();
        Phase phase;
        Entry(long sequence, Kind kind, GravesConfig grave, boolean broken, byte[] payload, Phase phase) {
            this.sequence = sequence; this.kind = kind; this.grave = grave; this.broken = broken;
            this.payload = payload; this.phase = phase;
            identity = grave.getUuid() + ":" + grave.getWorldId() + ":" + grave.getTime() + ":"
                    + grave.getLocation().getBlockX() + ":" + grave.getLocation().getBlockY() + ":" + grave.getLocation().getBlockZ();
        }
    }
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
            synchronized (this) { healthy = failure == null; initialized = failure == null; }
            wake();
            notifications.execute(() -> {
                try { loaded.complete(failure == null); }
                finally { if (failure != null) report("SQL recovery journal unavailable; new SQL transitions are blocked."); }
            });
        });
    }
    public CompletableFuture<Boolean> loaded() { return loaded.copy(); }
    public synchronized boolean isReady() { return healthy && !closing; }
    /** Record must already be detached and encoded on its Bukkit/Folia owner. */
    public synchronized Reservation reserve(Kind kind, GravesConfig record, boolean broken) {
        if (!healthy || closing || outstanding + evidenceEntries >= entryLimit || next == Long.MAX_VALUE) return null;
        FeatureIO.Admission admission = io.admit();
        if (admission == null) return null;
        try {
            byte[] payload = encode(next + 1, kind, record, broken);
            if (payload.length > byteLimit - bytes - evidenceBytes) { admission.close(); return null; }
            // The immutable wire representation drives both first execution and restart replay.
            Entry entry = decode(++next, payload); entry.phase = Phase.RESERVED;
            entries.put(entry.sequence, entry); bytes += payload.length; outstanding++;
            return new Reservation(entry, admission);
        } catch (IOException | RuntimeException invalid) { admission.close(); return null; }
    }
    public final class Reservation implements AutoCloseable {
        private final Entry entry;
        private FeatureIO.Admission admission;
        private Reservation(Entry entry, FeatureIO.Admission admission) { this.entry = entry; this.admission = admission; }
        /** Acknowledges the order-safe journal publication, independently of SQL completion. */
        public CompletableFuture<Disposition> durable() { return entry.durable.copy(); }
        /** Consumes the reserved slot; the returned future acknowledges SQL, never merely RAM admission. */
        public CompletableFuture<Boolean> commit() {
            FeatureIO.Admission ticket;
            synchronized (SqlRecoveryJournal.this) {
                if (admission == null) return entry.completion.copy();
                ticket = admission; admission = null; entry.phase = Phase.READY;
            }
            ticket.submit(() -> true).whenComplete((value, failure) -> {
                if (failure != null) fail(entry, "SQL recovery worker rejected an accepted transition.");
            });
            wake();
            return entry.completion.copy();
        }
        @Override public void close() {
            FeatureIO.Admission ticket;
            synchronized (SqlRecoveryJournal.this) {
                ticket = admission; admission = null;
                if (ticket == null) return;
                entries.remove(entry.sequence);
            }
            ticket.close();
            notifications.execute(() -> {
                try { entry.durable.complete(Disposition.CANCELLED); entry.completion.complete(false); }
                finally { release(entry); }
            });
            wake();
        }
    }
    /** Explicit retry signal; never blocks a Bukkit/region owner and never loops on a failed backend. */
    public void wake() {
        synchronized (this) { retryRequested = true; }
        continueDrain();
    }
    private void continueDrain() { io.requestDrain(this::drain); }
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
        synchronized (this) { if (draining || !initialized || (cursor == 0 && !retryRequested)) return; draining = true; }
        try {
            List<Entry> prefix = new ArrayList<>();
            synchronized (this) {
                if (closing) cursor = 0;
                if (cursor == 0) { blockedCycle.clear(); retryRequested = false; }
                int limit = closing ? entryLimit : 16;
                for (Entry entry : entries.values()) {
                    if (entry.sequence <= cursor) continue;
                    if (prefix.size() >= limit) break;
                    // Do not durably accept a successor whose missing predecessor could vanish on restart.
                    if (entry.phase == Phase.RESERVED) break;
                    prefix.add(entry);
                }
            }
            // Persist the entire bounded order-safe prefix before JDBC or user notifications.
            for (Entry entry : prefix) if (entry.phase != Phase.DURABLE) {
                try {
                    publish(entry);
                    synchronized (this) { entry.phase = Phase.DURABLE; }
                } catch (IOException journalFailure) {
                    synchronized (this) { healthy = false; }
                    // Resolve the known-durable prefix even when a later publication is uncertain.
                    for (Entry pending : prefix) {
                        if (pending.phase == Phase.DURABLE) {
                            notifyDurable(pending);
                            if (closing) fail(pending, "Durable SQL transition retained across shutdown; acknowledgment unavailable.", false);
                        }
                        else fail(pending, "SQL recovery journal publication held; replay remains possible.", true);
                    }
                    return;
                }
            }
            for (Entry entry : prefix) notifyDurable(entry);
            for (Entry entry : prefix) {
                if (blockedCycle.contains(entry.identity)) {
                    fail(entry, "Durable SQL predecessor remains unresolved; successor retained without acknowledgment.", false);
                    continue;
                }
                try {
                    boolean applied = true;
                    try { apply(entry); }
                    catch (SqlConditionNotMet miss) { applied = false; }
                    retire(entry);
                    synchronized (this) { entries.remove(entry.sequence, entry); }
                    boolean acknowledged = applied;
                    notifications.execute(() -> {
                        try { entry.completion.complete(acknowledged); }
                        finally { release(entry); }
                    });
                } catch (IOException journalFailure) {
                    synchronized (this) { healthy = false; }
                    fail(entry, "SQL completion journal retirement unavailable; retained for reconciliation.", false);
                    return;
                } catch (RuntimeException | Error databaseFailure) {
                    blockedCycle.add(entry.identity);
                    fail(entry, "SQL transition retained for durable recovery; SQL acknowledgment unavailable.", false);
                }
            }
            boolean more = false;
            synchronized (this) {
                if (!prefix.isEmpty()) cursor = prefix.getLast().sequence;
                for (Entry entry : entries.values()) if (entry.sequence > cursor) {
                    more = entry.phase != Phase.RESERVED; break;
                }
                if (!more) { cursor = 0; blockedCycle.clear(); }
            }
            synchronized (this) { more |= retryRequested; }
            if (more) continueDrain();
        } finally { synchronized (this) { draining = false; } }
    }
    private void notifyDurable(Entry entry) {
        if (!entry.durableNotified) {
            entry.durableNotified = true;
            notifications.execute(() -> entry.durable.complete(Disposition.DURABLE));
        }
    }
    private synchronized void release(Entry entry) {
        outstanding--; bytes -= entry.payload.length;
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
            case FLAG -> backend.setBroken(entry.grave, entry.broken);
            case DISPLAY -> backend.saveDisplayIdentity(entry.grave);
            case RECREATE -> backend.saveRecreatedGrave(entry.grave);
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
    private static byte[] readBounded(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < 0 || size > MAX_RECORD) throw new IOException("Invalid recovery file size");
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
                    long size = Files.size(path); if (size > MAX_RECORD || size > byteLimit - total) throw new IOException("Journal byte limit exceeded"); total += size;
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
    private static byte[] encode(long sequence, Kind kind, GravesConfig grave, boolean broken) throws IOException {
        if (grave.getUuid() == null || grave.getWorldId() == null || grave.getLocation() == null) throw new IOException("Complete SQL identity required");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new OutputStream() {
            @Override public void write(int value) throws IOException {
                if (bytes.size() >= MAX_RECORD - 32) throw new IOException("SQL recovery record exceeds limit"); bytes.write(value);
            }
            @Override public void write(byte[] value, int offset, int length) throws IOException {
                if (length > MAX_RECORD - 32 - bytes.size()) throw new IOException("SQL recovery record exceeds limit"); bytes.write(value, offset, length);
            }
        })) {
            output.writeLong(sequence); output.writeInt(VERSION); output.writeByte(kind.ordinal()); output.writeBoolean(broken);
            uuid(output, grave.getUuid()); uuid(output, grave.getWorldId()); output.writeLong(grave.getTime());
            output.writeInt(grave.getLocation().getBlockX()); output.writeInt(grave.getLocation().getBlockY()); output.writeInt(grave.getLocation().getBlockZ());
            text(output, Objects.toString(grave.getPlayerName(), "")); text(output, Objects.toString(grave.getDeathMessage(), ""));
            output.writeInt(grave.getExp()); output.writeBoolean(grave.isDestroyed()); output.writeLong(grave.getDestroyedTime());
            uuid(output, grave.getDisplayUUID()); uuid(output, grave.getInteractUUID()); text(output, Objects.toString(grave.getGraveDisplayType(), ""));
            text(output, kind == Kind.UPSERT || kind == Kind.UPDATE ? grave.encodedItemsForStorage() : "");
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
            Kind kind = Kind.values()[ordinal]; boolean broken = input.readBoolean();
            UUID player = uuid(input), world = uuid(input); long time = input.readLong();
            var location = new org.bukkit.Location(null, input.readInt(), input.readInt(), input.readInt());
            String name = text(input), death = text(input); int exp = input.readInt(); boolean destroyed = input.readBoolean(); long destroyedTime = input.readLong();
            UUID display = uuid(input), interact = uuid(input); String type = text(input), items = text(input);
            if (player == null || world == null || input.available() != 0) throw new IOException("Invalid recovery identity or trailing bytes");
            GravesConfig grave = new GravesConfig(player, name, location, new HashMap<>(), exp, death, time, destroyed, destroyedTime, display, interact, type);
            grave.setWorldId(world); grave.acceptPreparedSqlItems(items);
            return new Entry(sequence, kind, grave, broken, payload, Phase.DURABLE);
        }
    }
}
