package com.bencodez.gravestonesplus.graves;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Serializes all recipients at the grave identity; unresolved reservations never replay. */
public final class GraveClaimLedger {
    private final DurableFeatureState state;
    private final java.nio.file.Path archive;
    private final CompletableFuture<Boolean> loaded;
    private volatile boolean archiveReady;
    private final Map<String, String> recentPaid = new LinkedHashMap<>();
    private final Set<String> pending = new HashSet<>();
    public GraveClaimLedger(DurableFeatureState state) { this(state, null); }
    /** Archive completed identities separately, retaining bounded active state and lookup cache. */
    public GraveClaimLedger(DurableFeatureState state, java.nio.file.Path archive) {
        this.state = state; this.archive = archive == null ? null : archive.toAbsolutePath().normalize();
        if (archive == null) {
            archiveReady = true; loaded = state.loaded();
        } else loaded = state.loaded().thenCompose(ready -> {
            if (!ready) return CompletableFuture.completedFuture(false);
            return state.update(next -> {
                for (var entry : new ArrayList<>(next.entrySet())) if (entry.getValue().startsWith("PAID:")) {
                    archivePaid(entry.getKey(), entry.getValue());
                    next.remove(entry.getKey()); rememberPaid(entry.getKey(), entry.getValue());
                }
                return true;
            });
        }).handle((ready, failure) -> { archiveReady = failure == null && Boolean.TRUE.equals(ready); return archiveReady; });
    }
    public CompletableFuture<Boolean> loaded() { return loaded; }
    public boolean isReady() { return archiveReady && state.isReady(); }
    public synchronized boolean isBlocked(String grave) { return grave == null || !archiveReady || !state.isReady() || recentPaid.containsKey(grave) || pending.contains(grave) || state.snapshot().containsKey(grave); }
    public CompletableFuture<Boolean> canRecreate(String grave) {
        if (isBlocked(grave)) return CompletableFuture.completedFuture(false);
        return state.inspect(current -> !isBlocked(grave) && !current.containsKey(grave) && !archived(grave))
                .exceptionally(failure -> false);
    }
    public enum Restoration { AVAILABLE, QUEUED_LIMIT, RESERVED, PAID, UNAVAILABLE }
    /** Rejected/failed reads are unknown, never proof of absence or completed payout. */
    public CompletableFuture<Restoration> restoration(String grave) {
        if (grave == null || !archiveReady || !state.isReady()) return CompletableFuture.completedFuture(Restoration.UNAVAILABLE);
        FeatureIO.Admission admitted = state.admit();
        if (admitted == null) return CompletableFuture.completedFuture(Restoration.UNAVAILABLE);
        return admitted.submit(() -> {
            if (!state.isReady() || !archiveReady) return Restoration.UNAVAILABLE;
            if (archived(grave)) return Restoration.PAID;
            String value = state.snapshot().get(grave);
            if (value != null) return value.startsWith("PAID:") ? Restoration.PAID
                    : value.startsWith("QUEUED_LIMIT:") ? Restoration.QUEUED_LIMIT : Restoration.RESERVED;
            synchronized (this) { return pending.contains(grave) ? Restoration.RESERVED : Restoration.AVAILABLE; }
        }).exceptionally(failure -> Restoration.UNAVAILABLE);
    }
    public CompletableFuture<Boolean> isArchived(String grave) {
        return restoration(grave).thenApply(result -> result == Restoration.PAID);
    }
    public CompletableFuture<Boolean> reserve(String grave, UUID recipient) { return reserve(grave, recipient, ""); }
    public synchronized CompletableFuture<Boolean> reserve(String grave, UUID recipient, String evidence) {
        if (recipient == null || evidence == null || evidence.length() > 350_000 || isBlocked(grave) || pending.size() >= 128) return CompletableFuture.completedFuture(false);
        pending.add(grave);
        // Keep pending on uncertain publication/rejection: no payout, no unsafe retry.
        return state.update(next -> {
            // This disk lookup is on the serial persistence owner, before any payout.
            if (next.containsKey(grave) || archived(grave)) return false;
            next.put(grave, "RESERVED:" + recipient + (evidence.isEmpty() ? "" : ":" + evidence)); return true;
        }).thenApply(saved -> {
            if (!saved && state.isReady()) synchronized (this) { pending.remove(grave); }
            return saved;
        });
    }
    /** A queued ground payout has not performed any gameplay side effect and may resume. */
    public synchronized CompletableFuture<Boolean> queueLimit(String grave, UUID recipient, String evidence) {
        if (recipient == null || evidence == null || evidence.length() > 350_000 || isBlocked(grave) || pending.size() >= 128)
            return CompletableFuture.completedFuture(false);
        pending.add(grave);
        return state.update(next -> {
            if (next.containsKey(grave) || archived(grave)) return false;
            next.put(grave, "QUEUED_LIMIT:" + recipient + ":" + evidence); return true;
        }).thenApply(saved -> {
            if (!saved && state.isReady()) synchronized (this) { pending.remove(grave); }
            return saved;
        });
    }
    /** Publish non-replayable ownership before the first world payout operation. */
    public CompletableFuture<Boolean> reserveQueuedLimit(String grave, UUID recipient) {
        return state.update(next -> {
            String value = next.get(grave), prefix = "QUEUED_LIMIT:" + recipient + ":";
            if (value == null || !value.startsWith(prefix)) return false;
            next.put(grave, "RESERVED:" + recipient + ":" + value.substring(prefix.length())); return true;
        });
    }
    /** Only used before any item/XP mutation when the owning chunk disappears. */
    public CompletableFuture<Boolean> deferLimitReservation(String grave, UUID recipient) {
        return state.update(next -> {
            String value = next.get(grave), prefix = "RESERVED:" + recipient + ":";
            if (value == null || !value.startsWith(prefix)) return false;
            next.put(grave, "QUEUED_LIMIT:" + recipient + ":" + value.substring(prefix.length())); return true;
        });
    }
    public CompletableFuture<Boolean> cancelQueuedLimit(String grave) {
        return state.update(next -> {
            String value = next.get(grave);
            if (value == null || !value.startsWith("QUEUED_LIMIT:")) return false;
            next.remove(grave); return true;
        }).thenApply(saved -> { if (saved) synchronized (this) { pending.remove(grave); } return saved; });
    }
    public CompletableFuture<Boolean> cancel(String grave) {
        return state.update(next -> { next.remove(grave); return true; }).thenApply(saved -> {
            if (saved) synchronized (this) { pending.remove(grave); } return saved;
        });
    }
    public CompletableFuture<Boolean> paid(String grave, UUID recipient) {
        return state.update(next -> {
            String existing = next.get(grave);
            if (existing == null || !(existing.equals("RESERVED:" + recipient)
                    || existing.startsWith("RESERVED:" + recipient + ":") || existing.equals("PAID:" + recipient))) return false;
            String paid = "PAID:" + recipient;
            if (archive == null) next.put(grave, paid);
            else { archivePaid(grave, paid); next.remove(grave); }
            return true;
        }).thenApply(saved -> {
            if (saved) synchronized (this) { pending.remove(grave); rememberPaid(grave, "PAID:" + recipient); } return saved;
        });
    }
    public synchronized String status(String grave) { return recentPaid.containsKey(grave) ? recentPaid.get(grave) : state.snapshot().get(grave); }
    private synchronized void rememberPaid(String grave, String value) {
        recentPaid.put(grave, value);
        if (recentPaid.size() > 512) recentPaid.remove(recentPaid.keySet().iterator().next());
    }
    private java.nio.file.Path archiveFile(String grave) {
        try {
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(grave.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return archive.resolve(hash + ".yml");
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private boolean archived(String grave) {
        if (archive == null) return false;
        try {
            java.nio.file.Path file = archiveFile(grave);
            if (java.nio.file.Files.isSymbolicLink(archive) || java.nio.file.Files.isSymbolicLink(file))
                throw new java.io.IOException("unsafe claim archive path");
            if (java.nio.file.Files.notExists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
            var record = DurableFeatureState.yamlDisk(file, 1).load();
            String value = record.get(grave);
            if (record.size() != 1 || value == null || !value.matches("PAID:[a-f0-9-]{36}"))
                throw new java.io.IOException("invalid claim tombstone");
            UUID.fromString(value.substring(5));
            rememberPaid(grave, value); return true;
        } catch (Exception failure) { throw new IllegalStateException("Claim archive is unavailable; replay remains blocked", failure); }
    }
    private void archivePaid(String grave, String value) {
        if (archived(grave)) return;
        try {
            // Publish the tombstone before retiring the active reservation. Backend
            // deletion acknowledgments are unnecessary because replay identity is retained.
            DurableFeatureState.yamlDisk(archiveFile(grave), 1).save(Map.of(grave, value));
        } catch (Exception failure) { throw new IllegalStateException("Claim tombstone publication failed", failure); }
    }
    /** Preserve exact slot keys rather than the legacy encoded-array armour remapping. */
    public static String evidence(com.bencodez.gravestonesplus.storage.GravesConfig grave) {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("identity", GraveIdentity.of(grave)); yaml.set("experience", grave.getExp());
        for (var entry : grave.getItems().entrySet()) {
            if (entry.getKey() < -5 || entry.getKey() > 40) throw new IllegalArgumentException("unsupported grave slot");
            if (entry.getValue() != null) yaml.set("items." + entry.getKey(), entry.getValue().serialize());
        }
        byte[] bytes = yaml.saveToString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > 262_144) throw new IllegalArgumentException("grave recovery evidence exceeds 256 KiB");
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

}
