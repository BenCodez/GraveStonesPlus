package com.bencodez.gravestonesplus.storage;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import com.bencodez.gravestonesplus.graves.FeatureIO;

/** Bounded, ordered SQL obligations. Only the feature worker touches journal files or JDBC. */
public final class SqlRecoveryJournal {
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
    private long next, bytes;
    private boolean healthy, closing, draining;
    private static final class Entry {
        final long sequence;
        final Kind kind;
        final GravesConfig grave;
        final boolean broken;
        final byte[] payload;
        final String identity;
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
        io.submit(() -> { load(); return true; }).whenComplete((value, failure) -> {
            synchronized (this) { healthy = failure == null; }
            if (failure == null) { loaded.complete(true); wake(); }
            else { error.accept("SQL recovery journal unavailable; new SQL transitions are blocked."); loaded.complete(false); }
        });
    }
    public CompletableFuture<Boolean> loaded() { return loaded.copy(); }
    public synchronized boolean isReady() { return healthy && !closing; }
    /** Record must already be detached and encoded on its Bukkit/Folia owner. */
    public Reservation reserve(Kind kind, GravesConfig record, boolean broken) {
        byte[] payload;
        try { payload = encode(kind, record, broken); }
        catch (IOException | RuntimeException invalid) { return null; }
        synchronized (this) {
            if (!healthy || closing || payload.length > MAX_RECORD || entries.size() >= entryLimit
                    || payload.length > byteLimit - bytes || next == Long.MAX_VALUE) return null;
            FeatureIO.Admission admission = io.admit();
            if (admission == null) return null;
            Entry entry = new Entry(++next, kind, record, broken, payload, Phase.RESERVED);
            entries.put(entry.sequence, entry); bytes += payload.length;
            return new Reservation(entry, admission);
        }
    }
    public final class Reservation implements AutoCloseable {
        private final Entry entry;
        private FeatureIO.Admission admission;
        private Reservation(Entry entry, FeatureIO.Admission admission) { this.entry = entry; this.admission = admission; }
        /** Consumes the reserved slot; the returned future acknowledges SQL, never merely RAM admission. */
        public CompletableFuture<Boolean> commit() {
            FeatureIO.Admission ticket;
            synchronized (SqlRecoveryJournal.this) {
                if (admission == null) return entry.completion.copy();
                ticket = admission; admission = null; entry.phase = Phase.READY;
            }
            ticket.submit(() -> { drain(); return true; }).whenComplete((value, failure) -> {
                if (failure != null) fail(entry, "SQL recovery worker rejected an accepted transition.");
            });
            return entry.completion.copy();
        }
        @Override public void close() {
            FeatureIO.Admission ticket;
            synchronized (SqlRecoveryJournal.this) {
                ticket = admission; admission = null;
                if (ticket == null) return;
                entries.remove(entry.sequence); bytes -= entry.payload.length;
            }
            ticket.close(); entry.completion.complete(false); wake();
        }
    }
    /** Explicit retry signal; never blocks a Bukkit/region owner and never loops on a failed backend. */
    public void wake() { io.submit(() -> { drain(); return true; }); }
    public void close() { synchronized (this) { closing = true; } }
    /** Called on FeatureIO after accepted work, including its final shutdown drain. */
    public void drain() {
        synchronized (this) { if (draining || !loaded.isDone() || !Boolean.TRUE.equals(loaded.getNow(false))) return; draining = true; }
        try {
            Set<String> blocked = new HashSet<>();
            List<Entry> pass;
            synchronized (this) { pass = new ArrayList<>(entries.values()); }
            // At most entryLimit entries; never self-schedule retries during an outage.
            for (Entry entry : pass) {
                Phase phase;
                synchronized (this) { phase = entry.phase; }
                if (phase == Phase.RESERVED) { blocked.add(entry.identity); continue; }
                try {
                    if (phase != Phase.DURABLE) {
                        publish(entry);
                        synchronized (this) { entry.phase = Phase.DURABLE; }
                    }
                    if (blocked.contains(entry.identity)) continue;
                    boolean applied = true;
                    try { apply(entry); }
                    catch (SqlConditionNotMet miss) { applied = false; }
                    retire(entry);
                    synchronized (this) {
                        if (entries.remove(entry.sequence, entry)) bytes -= entry.payload.length;
                    }
                    entry.completion.complete(applied);
                } catch (IOException journalFailure) {
                    synchronized (this) { healthy = false; }
                    fail(entry, "SQL recovery journal could not be persisted; transition retained and new admissions blocked.");
                    return;
                } catch (RuntimeException | Error databaseFailure) {
                    blocked.add(entry.identity);
                    // A different identity may still complete. Never run a successor past this failed predecessor.
                    fail(entry, "SQL transition is retained for durable recovery; SQL acknowledgment unavailable.");
                }
            }
        } finally { synchronized (this) { draining = false; } }
    }
    private void fail(Entry entry, String message) {
        entry.completion.completeExceptionally(new IllegalStateException(message)); error.accept(message);
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
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer value = ByteBuffer.wrap(entry.payload);
            while (value.hasRemaining()) channel.write(value);
            channel.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        forceDirectory();
    }
    private void retire(Entry entry) throws IOException {
        Files.deleteIfExists(file(entry.sequence)); forceDirectory();
    }
    private void forceDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
    private void load() {
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)) throw new IOException("Journal directory cannot be a symbolic link");
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> listing = Files.newDirectoryStream(directory)) {
                for (Path path : listing) {
                    String name = path.getFileName().toString();
                    if (!name.matches("[0-9]{19}\\.sql")) {
                        if (name.matches("[0-9]{19}\\.sql\\.tmp")) throw new IOException("Interrupted journal publication requires reconciliation");
                        throw new IOException("Unexpected journal entry");
                    }
                    if (files.size() >= entryLimit || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IOException("Journal entry limit or type invalid");
                    files.add(path);
                }
            }
            files.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path path : files) {
                long size = Files.size(path);
                if (size > MAX_RECORD || size > byteLimit - bytes) throw new IOException("Journal byte limit exceeded");
                long sequence = Long.parseLong(path.getFileName().toString().substring(0, 19));
                byte[] payload = Files.readAllBytes(path);
                Entry entry = decode(sequence, payload);
                entries.put(sequence, entry); bytes += payload.length; next = Math.max(next, sequence);
            }
        } catch (IOException | RuntimeException invalid) { throw new IllegalStateException("SQL recovery journal could not be loaded", invalid); }
    }
    private static void text(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8); output.writeInt(bytes.length); output.write(bytes);
    }
    private static String text(DataInputStream input) throws IOException {
        int size = input.readInt(); if (size < 0 || size > MAX_RECORD || size > input.available()) throw new IOException("Invalid journal string length");
        return new String(input.readNBytes(size), java.nio.charset.StandardCharsets.UTF_8);
    }
    private static void uuid(DataOutputStream output, UUID value) throws IOException { output.writeBoolean(value != null); if (value != null) { output.writeLong(value.getMostSignificantBits()); output.writeLong(value.getLeastSignificantBits()); } }
    private static UUID uuid(DataInputStream input) throws IOException { return input.readBoolean() ? new UUID(input.readLong(), input.readLong()) : null; }
    private static byte[] encode(Kind kind, GravesConfig grave, boolean broken) throws IOException {
        if (grave.getUuid() == null || grave.getWorldId() == null || grave.getLocation() == null) throw new IOException("Complete SQL identity required");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(VERSION); output.writeByte(kind.ordinal()); output.writeBoolean(broken);
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
    private static Entry decode(long sequence, byte[] payload) throws IOException {
        if (payload.length < 32) throw new IOException("Truncated recovery entry");
        byte[] body = Arrays.copyOf(payload, payload.length - 32);
        if (!java.security.MessageDigest.isEqual(digest(body), Arrays.copyOfRange(payload, body.length, payload.length)))
            throw new IOException("Recovery entry checksum mismatch");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
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
