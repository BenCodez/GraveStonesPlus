package com.bencodez.gravestonesplus.graves;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.storage.GravesConfig;

/** Durable removal intents, indexed by chunk independently of historical grave retention. */
public final class GraveMarkerCleanup {
    private static final int LIMIT = 8192;
    private final GraveStonesPlus plugin;
    private final DurableFeatureState state;
    private final Set<Ticket> tickets = new HashSet<>();
    private volatile Map<ChunkKey, List<String>> index = Map.of();
    private boolean retired;
    private int cursor;
    private final com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask timer;
    private volatile boolean valid;
    private final CompletableFuture<Boolean> loaded;
    private record ChunkKey(UUID world, int x, int z) {}
    private record Marker(String id, UUID world, int x, int y, int z, Material material) {}
    public GraveMarkerCleanup(GraveStonesPlus plugin, DurableFeatureState state) {
        this.plugin = plugin; this.state = state;
        loaded = state.loaded().thenApply(ready -> {
            if (!ready) return false;
            try { reindex(); valid = true; return true; } catch (RuntimeException invalid) { plugin.getLogger().severe("Invalid marker cleanup journal; removals remain blocked until reconciliation."); return false; }
        });
        timer = plugin.getBukkitScheduler().getFoliaLib().getImpl().runTimer(this::retryLoaded, 20L, 100L);
    }
    public CompletableFuture<Boolean> loaded() { return loaded; }
    public static NamespacedKey identityKey(GraveStonesPlus plugin) { return new NamespacedKey(plugin, "grave_marker_identity"); }
    static NamespacedKey positionKey(GraveStonesPlus plugin, Block block) {
        return new NamespacedKey(plugin, "grave_marker_" + (block.getX() & 15) + "_" + block.getY() + "_" + (block.getZ() & 15));
    }
    /** Caller owns this block/region. A pending legacy record must never protect a tagged successor. */
    public static boolean hasConflictingIdentity(GraveStonesPlus plugin, Block block, String identity) {
        for (var value : block.getMetadata("Grave")) if (value.getOwningPlugin() == plugin && value.value() instanceof Grave grave
                && !identity.equals(GraveIdentity.of(grave.getGravesConfig()))) return true;
        String chunkId = block.getChunk().getPersistentDataContainer().get(positionKey(plugin, block), PersistentDataType.STRING);
        if (chunkId != null && !identity.equals(chunkId)) return true;
        if (block.getState() instanceof TileState tile) {
            String tileId = tile.getPersistentDataContainer().get(identityKey(plugin), PersistentDataType.STRING);
            if (tileId != null && !identity.equals(tileId)) return true;
        }
        return false;
    }
    /** Region-owned tag, including chunk PDC for non-tile barrier markers. */
    public static void tag(GraveStonesPlus plugin, Block block, String identity) {
        if (identity == null) return;
        block.getChunk().getPersistentDataContainer().set(positionKey(plugin, block), PersistentDataType.STRING, identity);
        if (block.getState() instanceof TileState tile) {
            tile.getPersistentDataContainer().set(identityKey(plugin), PersistentDataType.STRING, identity); tile.update();
        }
    }
    private static Marker marker(String id, String type) {
        String[] parts = id.split("/", -1);
        if (parts.length != 6) throw new IllegalArgumentException("Invalid cleanup identity");
        UUID.fromString(parts[0]); if (Long.parseLong(parts[1]) < 0 || !Set.of("PLAYER_HEAD", "CHEST", "BARRIER").contains(type)) throw new IllegalArgumentException("Invalid cleanup record");
        return new Marker(id, UUID.fromString(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), Material.valueOf(type));
    }
    private Material material(GravesConfig grave) {
        GraveDisplayType type;
        try { type = GraveDisplayType.valueOf(grave.getGraveDisplayType().trim().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException invalid) { type = plugin.getConfigFile().getGraveDisplayTypeEnum(); }
        if (type == null) return null;
        return switch (type) {
            case CHEST -> Material.CHEST; case DISPLAY_ENTITY -> Material.BARRIER; case PLAYER_HEAD -> Material.PLAYER_HEAD;
        };
    }
    public synchronized Ticket prepare(GravesConfig grave) {
        String id = GraveIdentity.of(grave);
        if (retired || !valid || id == null || !state.isReady() || tickets.size() >= 128 || tickets.stream().anyMatch(t -> t.id.equals(id))
                || !state.snapshot().containsKey(id) && state.snapshot().size() + tickets.size() >= LIMIT) return null;
        // Match existing marker normalization/fallback before reserving capacity.
        // A malformed public display setting must never leave an untracked admission.
        Material material = material(grave); if (material == null) return null;
        org.bukkit.Location location = grave.getLocation(); if (location == null) return null;
        World world = location.getWorld();
        FeatureIO.Admission admission = state.admit(); if (admission == null) return null;
        try {
            Ticket ticket = new Ticket(id, material.name(), world, admission); tickets.add(ticket); return ticket;
        } catch (RuntimeException | Error failure) { admission.close(); throw failure; }
    }
    public final class Ticket implements AutoCloseable {
        private final String id, type;
        private final FeatureIO.Admission admission;
        private final World world;
        private boolean used;
        private Ticket(String id, String type, World world, FeatureIO.Admission admission) { this.id = id; this.type = type; this.world = world; this.admission = admission; }
        public CompletableFuture<Boolean> commit() {
            synchronized (GraveMarkerCleanup.this) {
                if (used) return CompletableFuture.completedFuture(false);
                used = true;
            }
            return state.update(admission, next -> { next.put(id, type); return true; }).whenComplete((saved, failure) -> {
                // A queued publication still owns its journal slot until the acknowledged cache changes.
                synchronized (GraveMarkerCleanup.this) { tickets.remove(this); }
                if (failure == null && Boolean.TRUE.equals(saved)) { reindex(); dispatch(id, world); }
                else plugin.getLogger().warning("Grave marker cleanup intent unavailable; backend record must remain retained.");
            });
        }
        public void close() {
            synchronized (GraveMarkerCleanup.this) { if (used) return; used = true; tickets.remove(this); }
            admission.close();
        }
    }
    public CompletableFuture<Boolean> retain(GravesConfig grave) {
        Ticket ticket = prepare(grave); return ticket == null ? CompletableFuture.completedFuture(false) : ticket.commit();
    }
    public synchronized void retire() { retired = true; if (timer != null) timer.cancel(); for (Ticket ticket : List.copyOf(tickets)) ticket.close(); }
    private void reindex() {
        Map<ChunkKey, List<String>> next = new HashMap<>();
        for (var entry : state.snapshot().entrySet()) {
            Marker marker = marker(entry.getKey(), entry.getValue());
            next.computeIfAbsent(new ChunkKey(marker.world, marker.x >> 4, marker.z >> 4), key -> new ArrayList<>()).add(marker.id);
        }
        Map<ChunkKey,List<String>> fixed = new HashMap<>(); next.forEach((key, value) -> fixed.put(key, List.copyOf(value))); index = Map.copyOf(fixed);
    }
    public void onChunk(World world, int x, int z) { index.getOrDefault(new ChunkKey(world.getUID(), x, z), List.of()).stream().limit(128).forEach(id -> dispatch(id, world)); }
    /** Global scheduler resolves at most 128 recorded identities per pass; no world/chunk scans. */
    private synchronized void retryLoaded() {
        if (retired || !valid || !plugin.isEnabled()) return;
        List<String> ids = new ArrayList<>(state.snapshot().keySet()); if (ids.isEmpty()) return;
        for (int i = 0; i < Math.min(128, ids.size()); i++) {
            String id = ids.get((cursor + i) % ids.size()); Marker marker = marker(id, state.snapshot().get(id));
            World world = Bukkit.getWorld(marker.world); if (world != null) dispatch(id, world);
        }
        cursor = (cursor + Math.min(128, ids.size())) % ids.size();
    }
    private void dispatch(String id, World world) {
        String type = state.snapshot().get(id); if (type == null || retired || !plugin.isEnabled()) return;
        Marker marker = marker(id, type); if (world == null) return;
        Location location = new Location(world, marker.x, marker.y, marker.z);
        try { plugin.getBukkitScheduler().runTask(plugin, () -> {
            if (retired || !plugin.isEnabled() || !world.isChunkLoaded(marker.x >> 4, marker.z >> 4)) return;
            if (cleanup(marker, location)) state.update(next -> { next.remove(id); return true; }).thenAccept(saved -> { if (saved) reindex(); });
        }, location); } catch (RuntimeException rejected) { plugin.getLogger().warning("Marker cleanup is durably retained while its region scheduler is unavailable."); }
    }
    private boolean cleanup(Marker marker, Location location) {
        GraveRestoration restoration = plugin.getGraveRestoration();
        if (restoration != null && (restoration.isLoading() || restoration.hasPending(marker.id))) return false;
        // Recreation can legitimately reuse the same logical identity. An old intent
        // must be retired rather than destroying the now-active marker.
        if (plugin.activeGraveSnapshot().stream().anyMatch(grave -> !grave.isRemove() && !grave.getGravesConfig().isDestroyed()
                && marker.id.equals(GraveIdentity.of(grave.getGravesConfig())))) return true;
        Block block = location.getBlock(); Chunk chunk = block.getChunk();
        Object metadata = block.getMetadata("Grave").stream().filter(value -> value.getOwningPlugin() == plugin)
                .map(org.bukkit.metadata.MetadataValue::value).findFirst().orElse(null);
        String metadataId = metadata instanceof Grave grave ? GraveIdentity.of(grave.getGravesConfig()) : null;
        if (metadata instanceof Grave grave && !grave.isRemove() && !grave.getGravesConfig().isDestroyed() && marker.id.equals(metadataId)) return true;
        String chunkId = chunk.getPersistentDataContainer().get(positionKey(plugin, block), PersistentDataType.STRING);
        var blockState = block.getState();
        boolean tileMarker = blockState instanceof TileState;
        String tileId = tileMarker ? ((TileState) blockState).getPersistentDataContainer().get(identityKey(plugin), PersistentDataType.STRING) : null;
        boolean newer = metadataId != null && !marker.id.equals(metadataId) || chunkId != null && !marker.id.equals(chunkId) || tileId != null && !marker.id.equals(tileId);
        boolean owned = !newer && (marker.id.equals(metadataId) || marker.id.equals(tileId) || !tileMarker && marker.id.equals(chunkId));
        // Only this loaded chunk is inspected. Entity ownership survives a restart through PDC.
        for (Entity entity : chunk.getEntities()) if (marker.id.equals(entity.getPersistentDataContainer().get(identityKey(plugin), PersistentDataType.STRING))) entity.remove();
        if (newer) return true; // Old marker was replaced; never alter its successor.
        if (!owned) return block.getType() != marker.material; // Untagged legacy marker remains for conservative reconciliation.
        block.removeMetadata("Grave", plugin);
        chunk.getPersistentDataContainer().remove(positionKey(plugin, block));
        if (block.getType() == marker.material) block.setType(Material.AIR);
        return true;
    }
}
