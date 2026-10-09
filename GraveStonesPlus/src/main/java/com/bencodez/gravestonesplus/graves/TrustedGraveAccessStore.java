package com.bencodez.gravestonesplus.graves;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import com.bencodez.gravestonesplus.GraveStonesPlus;

/** Grants are visible only after durable acknowledgment. Failed revocation denies locally. */
public final class TrustedGraveAccessStore {
    public static final long MAX_LIFETIME = 90L * 24 * 60 * 60 * 1000;
    private final DurableFeatureState state;
    private final LongSupplier clock;
    private final Map<String, Long> denied = new HashMap<>();
    private final Map<String, Long> deniedGraves = new HashMap<>();
    private long operation;
    private boolean denyAll;
    public TrustedGraveAccessStore(GraveStonesPlus plugin) {
        this(new DurableFeatureState(plugin.getFeatureIO(), new java.io.File(plugin.getDataFolder(), "selected-access.yml").toPath(), 4096), System::currentTimeMillis);
    }
    public TrustedGraveAccessStore(DurableFeatureState state, LongSupplier clock) { this.state = state; this.clock = clock; }
    public CompletableFuture<Boolean> loaded() { return state.loaded(); }
    private static String key(String grave, UUID helper, TrustedGraveAccess.Kind kind) { return grave + "/" + helper + "/" + kind; }
    public synchronized CompletableFuture<Boolean> grantAsync(String grave, UUID helper, TrustedGraveAccess.Kind kind, long expiresAt) {
        long now = clock.getAsLong();
        if (grave == null || helper == null || kind == null || expiresAt <= now || expiresAt - now > MAX_LIFETIME) return CompletableFuture.completedFuture(false);
        String key = key(grave, helper, kind);
        final long admitted; synchronized (this) { admitted = ++operation; }
        return state.update(next -> {
            next.entrySet().removeIf(e -> expired(e.getValue(), clock.getAsLong()));
            next.put(key, Long.toString(expiresAt)); return true;
        }).thenApply(saved -> { if (saved) synchronized (this) { if (denied.getOrDefault(key, Long.MIN_VALUE) <= admitted) denied.remove(key); } return saved; });
    }
    private static boolean expired(String value, long now) {
        try { return Long.parseLong(value) <= now; } catch (RuntimeException failure) { return true; }
    }
    public synchronized CompletableFuture<Boolean> revokeAsync(String grave, UUID helper, TrustedGraveAccess.Kind kind) {
        if (grave == null || helper == null || kind == null) return CompletableFuture.completedFuture(false);
        String key = key(grave, helper, kind);
        final long admitted;
        synchronized (this) {
            admitted = ++operation;
            if (denied.size() >= 4096 && !denied.containsKey(key)) denyAll = true;
            else denied.put(key, admitted);
        }
        return state.update(next -> { next.remove(key); return true; }).thenApply(saved -> {
            if (saved) synchronized (this) { if (Objects.equals(denied.get(key), admitted)) denied.remove(key); } return saved;
        });
    }
    public synchronized boolean allows(String grave, UUID helper, TrustedGraveAccess.Kind kind) {
        if (grave == null || helper == null || kind == null || !state.isReady() || denyAll) return false;
        String key = key(grave, helper, kind);
        String value = state.snapshot().get(key);
        return !deniedGraves.containsKey(grave) && !denied.containsKey(key) && value != null && !expired(value, clock.getAsLong());
    }
    public List<TrustedGraveAccess> list(String grave) {
        if (grave == null || !state.isReady()) return List.of();
        String[] identity = grave.split("/");
        List<TrustedGraveAccess> result = new ArrayList<>();
        for (var entry : state.snapshot().entrySet()) if (entry.getKey().startsWith(grave + "/")) {
            try {
                String[] parts = entry.getKey().substring(grave.length() + 1).split("/");
                UUID helper = UUID.fromString(parts[0]); TrustedGraveAccess.Kind kind = TrustedGraveAccess.Kind.valueOf(parts[1]);
                if (allows(grave, helper, kind)) result.add(new TrustedGraveAccess(UUID.fromString(identity[0]), Long.parseLong(identity[1]), helper, kind, Long.parseLong(entry.getValue())));
            } catch (RuntimeException ignored) { }
        }
        return List.copyOf(result);
    }
    public synchronized CompletableFuture<Boolean> revokeForGraveAsync(String grave) {
        if (grave == null) return CompletableFuture.completedFuture(false);
        // Deny before publication, including if the grave is recreated while cleanup waits.
        long admitted = ++operation;
        if (deniedGraves.size() >= 4096 && !deniedGraves.containsKey(grave)) denyAll = true;
        else deniedGraves.put(grave, admitted);
        return state.update(next -> { next.keySet().removeIf(key -> key.startsWith(grave + "/")); return true; }).thenApply(saved -> {
            if (saved) synchronized (this) { if (Objects.equals(deniedGraves.get(grave), admitted)) deniedGraves.remove(grave); }
            return saved;
        });
    }
    // Preserve the initially introduced Java signatures for callers using owner/death keys.
    public CompletableFuture<Boolean> grantAsync(UUID owner, long time, UUID helper, TrustedGraveAccess.Kind kind, long expires) { return grantAsync(owner + "/" + time, helper, kind, expires); }
    public CompletableFuture<Boolean> revokeAsync(UUID owner, long time, UUID helper, TrustedGraveAccess.Kind kind) { return revokeAsync(owner + "/" + time, helper, kind); }
    public boolean allows(UUID owner, long time, UUID helper, TrustedGraveAccess.Kind kind) { return allows(owner + "/" + time, helper, kind); }
    public List<TrustedGraveAccess> list(UUID owner, long time) { return list(owner + "/" + time); }
    public CompletableFuture<Void> revokeForGraveAsync(UUID owner, long time) { return revokeForGraveAsync(owner + "/" + time).thenApply(saved -> null); }
}
