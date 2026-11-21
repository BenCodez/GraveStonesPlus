package com.bencodez.gravestonesplus.tests.graves;

import com.bencodez.gravestonesplus.graves.*;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerationLeaseTest {
    @TempDir Path directory;
    static <T> T await(CompletableFuture<T> f) throws Exception { return f.get(5, TimeUnit.SECONDS); }
    @Test void contenderCannotInitializeAndHealthyCloseReleasesStableInode() throws Exception {
        Object key;
        try (var first = new FeatureIO(4, directory)) {
            assertTrue(await(first.ready())); key = Files.readAttributes(directory.resolve("selected-generation.lock"), java.nio.file.attribute.BasicFileAttributes.class).fileKey();
            try (var next = new FeatureIO(4, directory)) {
                assertFalse(await(next.ready())); var initialized = new AtomicBoolean();
                assertThrows(ExecutionException.class, () -> await(next.submit(() -> initialized.compareAndSet(false, true))));
                assertFalse(initialized.get());
            }
        }
        try (var next = new FeatureIO(4, directory)) {
            assertTrue(await(next.ready()));
            assertEquals(key, Files.readAttributes(directory.resolve("selected-generation.lock"), java.nio.file.attribute.BasicFileAttributes.class).fileKey());
        }
    }
    @Test void actualNativeFrameKeepsLeaseAfterSynchronousDiskDrain() throws Exception {
        var first = new FeatureIO(4, directory); assertTrue(await(first.ready())); var actor = first.retainActor(); assertNotNull(actor);
        try {
            first.close(); assertTrue(first.isTerminated()); assertFalse(first.isGenerationReleased());
            try (var next = new FeatureIO(4, directory)) { assertFalse(await(next.ready())); }
        } finally { actor.close(); first.close(); }
        assertTrue(first.isGenerationReleased());
        try (var next = new FeatureIO(4, directory)) { assertTrue(await(next.ready())); }
    }
    @Test void classLoaderBoundaryDoesNotBypassOperatingSystemOwnership() throws Exception {
        try (var first = new FeatureIO(4, directory);
                var loader = new URLClassLoader(new java.net.URL[] { FeatureIO.class.getProtectionDomain().getCodeSource().getLocation() }, ClassLoader.getPlatformClassLoader())) {
            assertTrue(await(first.ready()));
            Class<?> type = Class.forName(FeatureIO.class.getName(), true, loader);
            Object next = type.getConstructor(int.class, Path.class).newInstance(4, directory);
            try { assertFalse((Boolean) await((CompletableFuture<?>) type.getMethod("ready").invoke(next))); }
            finally { type.getMethod("close").invoke(next); }
        }
    }
    @Test void cooperatingProcessCannotOpenTheOwnedStore() throws Exception {
        try (var first = new FeatureIO(4, directory)) {
            assertTrue(await(first.ready()));
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), LeaseProcess.class.getName(), directory.toString())
                    .redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
                assertTrue(new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("BLOCKED"));
            } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
        }
    }
    public static class LeaseProcess {
        public static void main(String[] args) throws Exception {
            try (var io = new FeatureIO(4, Path.of(args[0]))) { System.out.println(await(io.ready()) ? "ACQUIRED" : "BLOCKED"); }
        }
    }
    @Test void parentAliasIsPinnedRatherThanRedirectingPersistence() throws Exception {
        Path real = Files.createDirectory(directory.resolve("real")), alias = Files.createSymbolicLink(directory.resolve("alias"), real);
        try (var io = new FeatureIO(4, alias)) {
            assertTrue(await(io.ready())); Files.delete(alias); Files.createSymbolicLink(alias, Files.createDirectory(directory.resolve("other")));
            assertEquals(real, io.storeRoot(alias));
            await(io.submit(() -> { try { Files.writeString(io.storeRoot(alias).resolve("marker"), "synthetic"); return true; } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } }));
            assertTrue(Files.exists(real.resolve("marker"))); assertFalse(Files.exists(alias.resolve("marker")));
        }
    }
    @Test void linkedOrWrongTypeLockNeverInitializes() throws Exception {
        Path target = directory.resolve("target"); Files.writeString(target, "unchanged");
        Files.createSymbolicLink(directory.resolve("selected-generation.lock"), target);
        try (var io = new FeatureIO(4, directory)) { assertFalse(await(io.ready())); }
        assertEquals("unchanged", Files.readString(target));
        Files.delete(directory.resolve("selected-generation.lock")); Files.createDirectory(directory.resolve("selected-generation.lock"));
        try (var io = new FeatureIO(4, directory)) { assertFalse(await(io.ready())); }
    }
    @Test void replacedLockIsDetectedBeforeFurtherWorkAndOriginalCanBeRepaired() throws Exception {
        try (var io = new FeatureIO(4, directory)) {
            assertTrue(await(io.ready())); Path lock = directory.resolve("selected-generation.lock"), held = directory.resolve("held.lock");
            Files.move(lock, held); Files.createFile(lock); var ran = new AtomicBoolean();
            try { assertThrows(ExecutionException.class, () -> await(io.submit(() -> ran.compareAndSet(false, true)))); assertFalse(ran.get()); }
            finally { Files.delete(lock); Files.move(held, lock); }
            assertTrue(await(io.submit(() -> true)));
        }
    }
    @Test void unsupportedProviderDoesNotFallBackToRamOwnership() throws Exception {
        Path archive = directory.resolve("fixture.zip");
        try (var fs = FileSystems.newFileSystem(java.net.URI.create("jar:" + archive.toUri()), java.util.Map.of("create", "true"));
                var io = new FeatureIO(4, fs.getPath("/store"))) { assertFalse(await(io.ready())); }
    }
    @Test void actorAndSettlementTokensAreIdempotentAndBounded() throws Exception {
        try (var io = new FeatureIO(4, directory)) {
            assertTrue(await(io.ready())); var actors = new java.util.ArrayList<FeatureIO.Actor>();
            for (int i = 0; i < 128; i++) actors.add(java.util.Objects.requireNonNull(io.retainActor()));
            assertNull(io.retainActor()); actors.forEach(FeatureIO.Actor::close); actors.forEach(FeatureIO.Actor::close);
            var fence = io.retainUntilRecoverable(); assertNotNull(fence); fence.close(); fence.close();
        }
    }
}
