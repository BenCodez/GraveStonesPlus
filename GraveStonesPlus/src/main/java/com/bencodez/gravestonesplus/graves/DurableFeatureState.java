package com.bencodez.gravestonesplus.graves;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Immutable acknowledged cache; only the bounded disk owner reads/writes files. */
public final class DurableFeatureState {
    public interface Disk {
        Map<String, String> load() throws Exception;
        void save(Map<String, String> state) throws Exception;
    }
    private final FeatureIO io;
    private final Disk disk;
    private final int limit;
    private volatile Map<String, String> state = Map.of();
    private volatile boolean ready;
    private volatile boolean uncertain;
    private final CompletableFuture<Boolean> loaded;
    public DurableFeatureState(FeatureIO io, Path path, int limit) { this(io, yamlDisk(path, limit), limit); }
    public DurableFeatureState(FeatureIO io, Disk disk, int limit) {
        this.io = io; this.disk = disk; this.limit = limit;
        loaded = io.submit(() -> {
            try {
                Map<String, String> found = disk.load();
                if (found.size() > limit) throw new IOException("feature state exceeds capacity");
                state = Map.copyOf(found); ready = true; return true;
            } catch (Exception failure) { uncertain = true; return false; }
        });
    }
    public CompletableFuture<Boolean> loaded() { return loaded; }
    public boolean isReady() { return ready && !uncertain; }
    public Map<String, String> snapshot() { return state; }
    public FeatureIO.Admission admit() { return isReady() ? io.admit() : null; }
    /** Bounded read on the same owner as publication; archive lookups never run on gameplay threads. */
    public CompletableFuture<Boolean> inspect(Function<Map<String, String>, Boolean> inspection) {
        FeatureIO.Admission admitted = admit();
        if (admitted == null) return CompletableFuture.completedFuture(false);
        return admitted.submit(() -> isReady() && inspection.apply(state));
    }
    public CompletableFuture<Boolean> update(Function<Map<String, String>, Boolean> mutation) {
        FeatureIO.Admission admitted = admit();
        if (admitted == null) return CompletableFuture.completedFuture(false);
        return update(admitted, mutation);
    }
    public CompletableFuture<Boolean> update(FeatureIO.Admission admitted, Function<Map<String, String>, Boolean> mutation) {
        return admitted.submit(() -> {
            if (!isReady()) return false;
            Map<String, String> next = new HashMap<>(state);
            if (!mutation.apply(next)) return false;
            if (next.size() > limit) return false;
            try {
                disk.save(Map.copyOf(next));
                state = Map.copyOf(next); return true;
            } catch (Exception failure) {
                // A move/fsync failure can have published the successor. Deny until restart,
                // where a strict read reconciles it; never advertise a safe retry here.
                uncertain = true; return false;
            }
        });
    }
    @FunctionalInterface
    interface DirectoryChannel { FileChannel open(Path directory) throws IOException; }

    public static Disk yamlDisk(Path path, int limit) {
        // OpenJDK's native Windows provider cannot obtain directory handles through
        // FileChannel.open(READ). Other providers must retain directory fsync.
        boolean nativeWindows = path.getFileSystem().provider().getClass().getName()
                .equals("sun.nio.fs.WindowsFileSystemProvider");
        return yamlDisk(path, limit, nativeWindows, directory -> FileChannel.open(directory, StandardOpenOption.READ));
    }

    // Per-store seam exercises the real publication without changing global OS state.
    static Disk yamlDisk(Path path, int limit, boolean nativeWindows, DirectoryChannel channels) {
        return new Disk() {
            public Map<String, String> load() throws Exception {
                if (!Files.exists(path)) return Map.of();
                if (Files.size(path) > 4_194_304L) throw new IOException("feature file too large");
                YamlConfiguration yaml = new YamlConfiguration(); yaml.load(path.toFile());
                Map<String, String> result = new HashMap<>();
                ConfigurationSection entries = yaml.getConfigurationSection("entries");
                if (entries != null) for (String encoded : entries.getKeys(false)) {
                    if (result.size() >= limit) throw new IOException("feature state exceeds capacity");
                    String key = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                    Object value = entries.get(encoded);
                    if (!(value instanceof String)) throw new IOException("invalid feature entry");
                    result.put(key, (String) value);
                }
                // Preserve reminders from the initial uncommitted implementation.
                if (entries == null && yaml.contains("sent")) for (String key : yaml.getStringList("sent")) result.put(key, "sent");
                if (entries == null && !yaml.getKeys(false).isEmpty() && !yaml.contains("sent")
                        && !(yaml.getKeys(false).equals(Set.of("version")) && yaml.getInt("version") == 1)) throw new IOException("unknown feature file");
                return result;
            }
            public void save(Map<String, String> state) throws Exception {
                YamlConfiguration yaml = new YamlConfiguration(); yaml.set("version", 1); yaml.createSection("entries");
                for (var entry : state.entrySet()) {
                    String key = Base64.getUrlEncoder().withoutPadding().encodeToString(entry.getKey().getBytes(StandardCharsets.UTF_8));
                    yaml.set("entries." + key, entry.getValue());
                }
                byte[] bytes = yaml.saveToString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 4_194_304) throw new IOException("feature file too large");
                publishBytes(path, bytes, nativeWindows, channels);
            }
        };
    }

    /** Publish an already serialized document; callers retain serialization ownership. */
    public static void publishDocument(Path path, String document) throws IOException {
        boolean nativeWindows = path.getFileSystem().provider().getClass().getName()
                .equals("sun.nio.fs.WindowsFileSystemProvider");
        publishBytes(path, document.getBytes(StandardCharsets.UTF_8), nativeWindows,
                directory -> FileChannel.open(directory, StandardOpenOption.READ));
    }

    private static void publishBytes(Path path, byte[] bytes, boolean nativeWindows,
            DirectoryChannel channels) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), path.getFileName().toString(), ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            synchronizeDirectory(path.toAbsolutePath().getParent(), nativeWindows, channels);
        } finally { Files.deleteIfExists(temp); }
    }

    private static void synchronizeDirectory(Path parent, boolean nativeWindows, DirectoryChannel channels) throws IOException {
        final FileChannel directory;
        try { directory = channels.open(parent); }
        catch (AccessDeniedException unsupportedHandle) {
            // Windows lacks a Java directory handle; the successor file has already
            // been forced and atomically replaced. Do not swallow force failures or
            // any errors from POSIX/custom providers, missing directories or files.
            if (!nativeWindows || !parent.toString().equals(unsupportedHandle.getFile()) || !Files.isDirectory(parent))
                throw unsupportedHandle;
            return;
        }
        try (directory) { directory.force(true); }
    }
}
