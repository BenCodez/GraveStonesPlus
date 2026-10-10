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
    private final GraveStonesPlus plugin;
    private final Map<String, Long> denied = new HashMap<>();
    private final Map<String, Long> deniedGraves = new HashMap<>();
    private long operation;
    private boolean denyAll;
    public TrustedGraveAccessStore(GraveStonesPlus plugin) {
        this(new DurableFeatureState(plugin.getFeatureIO(), new java.io.File(plugin.getDataFolder(), "selected-access.yml").toPath(), 4096), System::currentTimeMillis, plugin);
    }
    public TrustedGraveAccessStore(DurableFeatureState state, LongSupplier clock) { this(state, clock, null); }
    TrustedGraveAccessStore(DurableFeatureState state, LongSupplier clock, GraveStonesPlus plugin) {
        this.state = state; this.clock = clock; this.plugin = plugin;
    }
    public CompletableFuture<Boolean> loaded() { return state.loaded(); }
    public boolean isReady() { return state.isReady(); }
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
    /** Resolve an owner/death reference only when it identifies one exact stored grave. */
    private String legacyIdentity(UUID owner, long time) {
        if (owner == null) return null;
        String prefix = owner + "/" + time + "/";
        Set<String> identities = new HashSet<>();
        if (plugin != null) {
            for (List<Grave> records : List.of(plugin.activeGraveSnapshot(), plugin.brokenGraveSnapshot()))
                for (Grave grave : records) {
                    String identity = GraveIdentity.of(grave.getGravesConfig());
                    if (identity != null && identity.startsWith(prefix)) identities.add(identity);
                }
        }
        // A standalone store can resolve already-persisted canonical grants after restart.
        // Shortened legacy rows cannot establish location ownership and are never promoted.
        for (String key : state.snapshot().keySet()) {
            String[] parts = key.split("/", -1);
            if (parts.length != 8 || !key.startsWith(prefix)) continue;
            try {
                UUID.fromString(parts[2]); Integer.parseInt(parts[3]); Integer.parseInt(parts[4]); Integer.parseInt(parts[5]);
                UUID.fromString(parts[6]); TrustedGraveAccess.Kind.valueOf(parts[7]);
                identities.add(String.join("/", Arrays.copyOf(parts, 6)));
            } catch (IllegalArgumentException invalid) { /* A malformed row cannot establish grave ownership. */ }
        }
        return identities.size() == 1 ? identities.iterator().next() : null;
    }
    // Preserve owner/death signatures without writing keys the real authorization path cannot read.
    public CompletableFuture<Boolean> grantAsync(UUID owner, long time, UUID helper, TrustedGraveAccess.Kind kind, long expires) {
        return grantAsync(legacyIdentity(owner, time), helper, kind, expires);
    }
    public CompletableFuture<Boolean> revokeAsync(UUID owner, long time, UUID helper, TrustedGraveAccess.Kind kind) {
        return revokeAsync(legacyIdentity(owner, time), helper, kind);
    }
    public boolean allows(UUID owner, long time, UUID helper, TrustedGraveAccess.Kind kind) {
        return allows(legacyIdentity(owner, time), helper, kind);
    }
    public List<TrustedGraveAccess> list(UUID owner, long time) { return list(legacyIdentity(owner, time)); }
    public CompletableFuture<Void> revokeForGraveAsync(UUID owner, long time) {
        String identity = legacyIdentity(owner, time);
        if (identity == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Grave reference is missing or ambiguous"));
        return revokeForGraveAsync(identity).thenCompose(saved -> Boolean.TRUE.equals(saved)
                ? CompletableFuture.completedFuture(null)
                : CompletableFuture.failedFuture(new IllegalStateException("Grave access revocation was not persisted")));
    }
}
