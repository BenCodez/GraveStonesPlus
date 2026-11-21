package com.bencodez.gravestonesplus.graves;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;

/** Cooperative installation ownership. Never delete the stable lock inode on release. */
final class StoreGenerationLease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final Path root, path;
    private final Object rootKey, fileKey;
    private StoreGenerationLease(FileChannel channel, FileLock lock, Path root, Path path) throws IOException {
        this.channel = channel; this.lock = lock; this.root = root; this.path = path;
        this.rootKey = key(root); this.fileKey = key(path);
    }
    private static Object key(Path path) throws IOException {
        Object key = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        if (key == null) throw new IOException("Installation lease provider lacks stable file identity");
        return key;
    }
    static StoreGenerationLease acquire(Path configured) throws IOException {
        Files.createDirectories(configured);
        // Existing parent aliases are supported, but never follow a lock-file link.
        Path root = configured.toRealPath();
        Path path = root.resolve("selected-generation.lock");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid installation lease path");
        // CREATE_NEW first gives both readers the same persistent inode. No truncate.
        try { Files.createFile(path); } catch (FileAlreadyExistsException existing) { }
        Object before = key(path);
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        boolean transferred = false;
        try {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException held) { throw new IOException("Installation generation is already owned", held); }
            if (lock == null) throw new IOException("Installation generation is already owned");
            StoreGenerationLease result = new StoreGenerationLease(channel, lock, root, path);
            if (!before.equals(result.fileKey)) throw new IOException("Installation lease changed during acquisition");
            result.check(); transferred = true; return result;
        } finally { if (!transferred) channel.close(); }
    }
    Path root() { return root; }
    /** Detect a changed canonical path before authorizing further store work. */
    void check() throws IOException {
        if (!lock.isValid() || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || !rootKey.equals(key(root)) || !fileKey.equals(key(path))) throw new IOException("Installation lease unavailable");
    }
    @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
