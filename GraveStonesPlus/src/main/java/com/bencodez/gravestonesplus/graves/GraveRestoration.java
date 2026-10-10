package com.bencodez.gravestonesplus.graves;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.storage.*;

/** Serial, bounded admission before any startup marker metadata, navigation or timer exposure. */
public final class GraveRestoration {
    private static final int MAX_BATCH = 128;
    private final GraveStonesPlus plugin;
    private final GraveStorageManager storage;
    private final Map<String, GravesConfig> pending = new LinkedHashMap<>();
    private final Map<String, Grave> admitted = new HashMap<>();
    private final Set<String> published = new HashSet<>();
    private final Set<String> conflicts = ConcurrentHashMap.newKeySet();
    private final Map<ChunkKey, Set<String>> chunks = new HashMap<>();
    // Shares pending record ownership and retention; never scans worlds or publishes a live Grave.
    private final Map<PositionKey, Set<String>> positions = new HashMap<>();
    private record ChunkKey(UUID world, int x, int z) {}
    private record PositionKey(UUID world, int x, int y, int z) {
        ChunkKey chunk() { return new ChunkKey(world, x >> 4, z >> 4); }
    }
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean retired;
    private volatile boolean recordsLoaded;
    private int cursor;
    private com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask timer;
    public GraveRestoration(GraveStonesPlus plugin, GraveStorageManager storage) { this.plugin = plugin; this.storage = storage; }
    /** Coalesce a durable limit request into the existing bounded restoration/chunk retry lane. */
    public synchronized void retainLimit(Grave grave) {
        GravesConfig record = grave.getGravesConfig(); String identity = GraveIdentity.of(record);
        pending.put(identity, record); admitted.put(identity, grave); published.add(identity);
        index(identity, record);
    }
    public void start() {
        storage.beginRestoration();
        // Flat-file initialization already decoded the document. Index plain record
        // identity/position fields before listeners open, even on Folia's global
        // startup context. Item cloning/encoding and claim admission stay deferred.
        if (!storage.isMySQL()) {
            try {
                var cached = cachedFlatRecords();
                indexCachedRecords(cached);
                if (org.bukkit.Bukkit.isPrimaryThread()) acceptRecords(cached);
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Cached grave records remain withheld until owner preparation can retry.");
            }
        }
        // Even an unavailable archive must leave backend records retained for shutdown.
        retry();
        timer = plugin.getBukkitScheduler().getFoliaLib().getImpl().runTimer(this::retry, 20L, 100L);
    }
    public void retry() {
        if (retired || !plugin.isEnabled() || !running.compareAndSet(false, true)) return;
        if (!recordsLoaded) {
            CompletableFuture<GraveStorageManager.RestorationRecords> read = storage.isMySQL()
                    ? storage.loadMySQLForRestoration()
                    : loadCachedFlatOnOwner();
            read.thenCompose(this::acceptRecordsOnOwner).whenComplete((loaded, error) -> {
            if (error != null) { running.set(false); plugin.getLogger().warning("Stored graves remain withheld until backend restoration can retry."); }
            else cycle();
        });
        } else cycle();
    }
    private GraveStorageManager.RestorationRecords cachedFlatRecords() {
        return new GraveStorageManager.RestorationRecords(storage.loadGraves(), storage.loadBrokenGraves());
    }
    private CompletableFuture<GraveStorageManager.RestorationRecords> loadCachedFlatOnOwner() {
        var result = new CompletableFuture<GraveStorageManager.RestorationRecords>();
        try { plugin.getBukkitScheduler().runTask(plugin, () -> {
            if (retired || !plugin.isEnabled()) { result.completeExceptionally(new IllegalStateException("Grave restoration retired")); return; }
            try { result.complete(cachedFlatRecords()); }
            catch (RuntimeException failure) { result.completeExceptionally(failure); }
        }); } catch (RuntimeException rejected) { result.completeExceptionally(rejected); }
        return result;
    }
    private CompletableFuture<Void> acceptRecordsOnOwner(GraveStorageManager.RestorationRecords records) {
        var result = new CompletableFuture<Void>();
        Runnable accept = () -> {
            if (retired || !plugin.isEnabled()) { result.completeExceptionally(new IllegalStateException("Grave restoration retired")); return; }
            try { acceptRecords(records); result.complete(null); }
            catch (RuntimeException failure) { result.completeExceptionally(failure); }
        };
        try { plugin.getBukkitScheduler().runTask(plugin, accept); }
        catch (RuntimeException rejected) { result.completeExceptionally(rejected); }
        return result;
    }
    private synchronized void indexCachedRecords(GraveStorageManager.RestorationRecords records) {
        // Borrow already-loaded records only until owner preparation. Do not construct
        // GravesConfig, resolve worlds, serialize inventories or publish live Graves here.
        for (GravesConfig grave : concat(records.active(), records.broken())) {
            String identity = GraveIdentity.of(grave);
            if (identity != null) { pending.put(identity, grave); index(identity, grave); }
        }
    }
    private void acceptRecords(GraveStorageManager.RestorationRecords records) {
        List<GravesConfig> active = records.active(), broken = records.broken();
        storage.retainRestoration(active, broken);
        synchronized (this) {
            int invalid = 0;
            for (GravesConfig grave : concat(active, broken)) {
                String identity = GraveIdentity.of(grave); String key = identity == null ? "unresolved-" + invalid++ : identity;
                pending.put(key, grave);
                if (identity != null) index(key, grave);
            }
            recordsLoaded = true;
        }
    }
    private static List<GravesConfig> concat(List<GravesConfig> active, List<GravesConfig> broken) {
        List<GravesConfig> result = new ArrayList<>(active); result.addAll(broken); return result;
    }
    private synchronized void cycle() {
        List<String> all = new ArrayList<>(pending.keySet());
        if (retired || all.isEmpty()) { running.set(false); return; }
        List<String> batch = new ArrayList<>();
        for (int i = 0; i < Math.min(MAX_BATCH, all.size()); i++) batch.add(all.get((cursor + i) % all.size()));
        cursor = (cursor + batch.size()) % all.size(); process(batch.iterator());
    }
    private void process(Iterator<String> batch) {
        if (retired || !plugin.isEnabled() || !batch.hasNext()) { running.set(false); return; }
        String key = batch.next(); GravesConfig record;
        synchronized (this) { record = pending.get(key); }
        if (record == null) { process(batch); return; }
        plugin.getGraveClaimLedger().restoration(GraveIdentity.of(record)).whenComplete((decision, error) -> {
            if (retired || !plugin.isEnabled()) { running.set(false); return; }
            if (error != null || decision == GraveClaimLedger.Restoration.UNAVAILABLE) { process(batch); return; }
            if (decision == GraveClaimLedger.Restoration.RESERVED) { process(batch); return; }
            Location loc = record.getLocation();
            if (loc != null && loc.getWorld() == null && record.getWorldId() != null) {
                try {
                    plugin.getBukkitScheduler().runTask(plugin, () -> {
                        if (retired || !plugin.isEnabled()) { running.set(false); return; }
                        try {
                            loc.setWorld(org.bukkit.Bukkit.getWorld(record.getWorldId()));
                            processResolved(batch, key, record, decision, loc);
                        } catch (RuntimeException unavailable) { running.set(false); }
                    });
                } catch (RuntimeException rejected) { running.set(false); }
            } else processResolved(batch, key, record, decision, loc);
        });
    }
    private void processResolved(Iterator<String> batch, String key, GravesConfig record,
            GraveClaimLedger.Restoration decision, Location loc) {
            // Both active and historical records require a resolved world before publication.
            // Keep unavailable worlds pending so menus and recreation never see a null world.
            if (loc == null || loc.getWorld() == null) { process(batch); return; }
            if (decision == GraveClaimLedger.Restoration.PAID || record.isDestroyed()) {
                plugin.getMarkerCleanup().retain(record).whenComplete((saved, failure) -> {
                    if (retired || !plugin.isEnabled()) { running.set(false); return; }
                    if (!Boolean.TRUE.equals(saved) || failure != null) { process(batch); return; }
                    try { plugin.getBukkitScheduler().runTask(plugin, () -> {
                        if (retired || !plugin.isEnabled()) { running.set(false); return; }
                        try {
                            record.setDestroyed(true); if (record.getDestroyedTime() == 0) record.setDestroyedTime(System.currentTimeMillis());
                            long lifetime = com.bencodez.simpleapi.time.ParsedDuration.parse(plugin.getConfigFile().getBrokenGraveTimeLimit(), TimeUnit.HOURS).getMillis();
                            if (lifetime <= 0 || System.currentTimeMillis() - record.getDestroyedTime() <= lifetime) plugin.restoreBrokenRecord(new Grave(plugin, record));
                            resolved(key, record); persistResolvedRemoval(record);
                        } finally { process(batch); }
                    }); } catch (RuntimeException rejected) { running.set(false); }
                });
                return;
            }
            try {
                plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtLocation(loc, task -> {
                    if (retired || !plugin.isEnabled()) return;
                    // Recheck the process-local reservation fence after the asynchronous archive decision.
                    if (decision != GraveClaimLedger.Restoration.QUEUED_LIMIT && plugin.getGraveClaimLedger().isBlocked(GraveIdentity.of(record))) return;
                    Grave grave;
                    synchronized (this) {
                        grave = admitted.get(key);
                        if (grave == null) {
                            grave = new Grave(plugin, record); admitted.put(key, grave);
                        }
                    }
                    if (grave.isRemove() || record.isDestroyed()) { resolved(key, record); return; }
                    boolean loaded = loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
                    // Material alone cannot prove ownership: a newer grave may occupy this position.
                    // Keep the old record pending without publishing it or changing the successor.
                    if (loaded && GraveMarkerCleanup.hasConflictingIdentity(plugin, loc.getBlock(), GraveIdentity.of(record))) {
                        boolean firstConflict;
                        synchronized (this) { firstConflict = conflicts.add(key); published.remove(key); }
                        plugin.withholdRestoredRecord(grave);
                        grave.removeTimer();
                        if (firstConflict) plugin.getGraveNavigation().invalidate(GraveIdentity.of(record));
                        return;
                    }
                    synchronized (this) {
                        // An unloaded chunk cannot establish that a previously observed successor went away.
                        if (!loaded && conflicts.contains(key)) return;
                        if (loaded) conflicts.remove(key);
                    }
                    if (decision == GraveClaimLedger.Restoration.QUEUED_LIMIT) {
                        grave.markLimitPayoutPending();
                        synchronized (this) {
                            if (!published.contains(key)) {
                                grave.checkTimeLimit(plugin.getConfigFile().getGraveTimeLimit());
                                if (grave.isRemove() || record.isDestroyed()) { resolved(key, record); return; }
                                plugin.restoreActiveRecord(grave); published.add(key);
                            }
                        }
                        if (loaded) grave.resumeLimitPayout();
                        return;
                    }
                    if (loaded && !grave.isValid()) {
                        var removal = new com.bencodez.gravestonesplus.events.GraveRemoveEvent(grave, com.bencodez.gravestonesplus.events.GraveRemoveReason.INVALID);
                        org.bukkit.Bukkit.getPluginManager().callEvent(removal);
                        if (removal.isCancelled()) return;
                        // An integration can repair the marker while observing the event.
                        if (!grave.isValid()) {
                            Grave invalid = grave;
                            plugin.getMarkerCleanup().retain(record).thenCompose(saved -> {
                                if (!saved || retired || !plugin.isEnabled()) return CompletableFuture.completedFuture(false);
                                // A recreated historical grave retains its identity. Revoke durably
                                // before publishing removal so old helpers cannot inherit recreation.
                                return plugin.getTrustedGraveAccessStore().revokeForGraveAsync(GraveIdentity.of(record));
                            }).thenAccept(saved -> {
                                if (saved && !retired && plugin.isEnabled()) plugin.getBukkitScheduler().runTask(plugin, () -> {
                                    if (retired || !plugin.isEnabled() || invalid.isValid()) return;
                                    plugin.getGraveNavigation().invalidate(GraveIdentity.of(record));
                                    record.setDestroyed(true); record.setDestroyedTime(System.currentTimeMillis());
                                    plugin.removeGrave(invalid); plugin.restoreBrokenRecord(invalid); resolved(key, record); persistResolvedRemoval(record);
                                }, loc);
                            }); return;
                        }
                    }
                    // Set the authoritative deadline before logical publication, without probing an unloaded marker.
                    synchronized (this) {
                        if (!published.contains(key)) {
                            grave.checkTimeLimit(plugin.getConfigFile().getGraveTimeLimit());
                            // Native Bukkit expiration can remove synchronously during this call.
                            if (grave.isRemove() || record.isDestroyed()) { resolved(key, record); return; }
                            plugin.restoreActiveRecord(grave); published.add(key);
                        }
                    }
                    if (!loaded) return;
                    grave.loadBlockMeta(loc.getBlock()); grave.removeHologramsAround(); grave.checkBlockDisplayAndFixIfMissing(); grave.createHologram();
                    resolved(key, record);
                }).whenComplete((unused, failure) -> process(batch));
            } catch (RuntimeException rejected) { running.set(false); }
    }
    private void persistResolvedRemoval(GravesConfig record) {
        if (storage.isMySQL()) storage.updateExistingGrave(record, true);
        else { storage.saveBrokenGravesFromObjects(plugin.brokenGraveSnapshot()); storage.saveGravesFromObjects(plugin.activeGraveSnapshot()); }
    }
    private synchronized void resolved(String key, GravesConfig record) {
        pending.remove(key); admitted.remove(key); published.remove(key); conflicts.remove(key);
        // The key retains the original position even if an integration moved its mutable record.
        if (key != null && !key.startsWith("unresolved-")) {
            String[] identity = key.split("/", -1);
            PositionKey position = new PositionKey(UUID.fromString(identity[2]), Integer.parseInt(identity[3]), Integer.parseInt(identity[4]), Integer.parseInt(identity[5]));
            Set<String> exact = positions.get(position); if (exact != null) { exact.remove(key); if (exact.isEmpty()) positions.remove(position); }
            Set<String> values = chunks.get(position.chunk()); if (values != null) { values.remove(key); if (values.isEmpty()) chunks.remove(position.chunk()); }
        }
        storage.restorationResolved(record);
    }
    private void index(String identity, GravesConfig record) {
        Location loc = record.getLocation();
        if (identity == null || loc == null || record.getWorldId() == null) return;
        PositionKey position = new PositionKey(record.getWorldId(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        if (!record.isDestroyed()) positions.computeIfAbsent(position, ignored -> new LinkedHashSet<>()).add(identity);
        chunks.computeIfAbsent(position.chunk(), ignored -> new LinkedHashSet<>()).add(identity);
    }
    /** Event/region-owner-only read: protect an exact pending marker without admitting claims. */
    public boolean protectsPendingMarker(Block block) {
        return block != null && pendingMarkerIdentity(block, block.getType()) != null;
    }
    /** Expected material also lets a deferred physics repair recheck its original ownership. */
    public synchronized String pendingMarkerIdentity(Block block, Material expected) {
        if (retired || block == null || !plugin.isEnabled()) return null;
        PositionKey position = new PositionKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        Set<String> candidates = positions.get(position);
        if (candidates == null || !block.getWorld().isChunkLoaded(position.x() >> 4, position.z() >> 4)) return null;
        for (String identity : candidates) {
            GravesConfig record = pending.get(identity); Grave grave = admitted.get(identity);
            if (record == null || record.isDestroyed() || grave != null && grave.isRemove()
                    || !identity.equals(GraveIdentity.of(record)) || markerMaterial(record) != expected
                    || GraveMarkerCleanup.hasConflictingIdentity(plugin, block, identity)) continue;
            return identity;
        }
        return null;
    }
    private Material markerMaterial(GravesConfig record) {
        GraveDisplayType type;
        try { type = GraveDisplayType.valueOf(record.getGraveDisplayType().trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException invalid) { type = plugin.getConfigFile().getGraveDisplayTypeEnum(); }
        if (type == null) return null;
        return switch (type) { case CHEST -> Material.CHEST; case DISPLAY_ENTITY -> Material.BARRIER; case PLAYER_HEAD -> Material.PLAYER_HEAD; };
    }
    public synchronized void onChunk(UUID world, int x, int z) {
        if (retired || !recordsLoaded || !running.compareAndSet(false, true)) return;
        // Immediately completed UNAVAILABLE decisions recurse on this owner. Keep
        // native chunk admission bounded like the timer, leaving the rest indexed.
        process(chunks.getOrDefault(new ChunkKey(world, x, z), Set.of()).stream().limit(MAX_BATCH).toList().iterator());
    }
    public synchronized boolean isLoading() { return !recordsLoaded; }
    public boolean isConflicted(String identity) { return identity != null && conflicts.contains(identity); }
    public synchronized boolean hasPending(String identity) { return identity != null && pending.containsKey(identity); }
    public void retire() { retired = true; if (timer != null) timer.cancel(); }
}
