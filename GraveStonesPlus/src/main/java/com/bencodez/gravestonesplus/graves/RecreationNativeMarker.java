package com.bencodez.gravestonesplus.graves;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.storage.*;

/** Attempt-owned native resources. Location methods never inspect a deferred entity off its owner. */
final class RecreationNativeMarker {
    private enum Outcome { UNUSED, CANCELLED, PENDING, ACKNOWLEDGED, MISSED, HELD }
    private final GraveStonesPlus plugin;
    private final Grave grave;
    private final GraveStorageManager storage;
    private final GraveStorageManager.RecreationAdmission admission;
    private final GraveDisplayType type;
    private final Material material;
    private final UUID attemptId = UUID.randomUUID(), owner, worldId;
    private final long time;
    private final String identity;
    private final Location location;
    private final AtomicBoolean started = new AtomicBoolean(), running = new AtomicBoolean(), retired = new AtomicBoolean();
    private final AtomicBoolean submitted = new AtomicBoolean(), adoptionRequested = new AtomicBoolean(), adoptionValid = new AtomicBoolean();
    private final AtomicBoolean rollbackRequested = new AtomicBoolean(), revocationRequested = new AtomicBoolean();
    private final CompletableFuture<Boolean> rollback = new CompletableFuture<>(), revocation = new CompletableFuture<>();
    private final CompletableFuture<Boolean> adoption = new CompletableFuture<>();
    private final CompletableFuture<Boolean> sql = new CompletableFuture<>();
    private volatile Outcome outcome = Outcome.UNUSED;
    private volatile ItemDisplay display;
    private volatile UUID displayId;
    private volatile boolean created;
    private String expectedName;
    private UUID expectedSkullOwner;
    private boolean spawnAttempted, ownershipWriteStarted, blockChanged, tileIdentityAdded;
    private Block block;
    private BlockData previousData;
    private String previousIdentity, expectedData;

    RecreationNativeMarker(GraveStonesPlus plugin, Grave grave,
            GraveStorageManager.RecreationAdmission admission, GraveDisplayType type) {
        this.plugin = plugin; this.grave = grave; this.storage = plugin.getStorageManager();
        this.admission = Objects.requireNonNull(admission); this.type = Objects.requireNonNull(type);
        material = switch (type) { case CHEST -> Material.CHEST; case PLAYER_HEAD -> Material.PLAYER_HEAD; case DISPLAY_ENTITY -> Material.BARRIER; };
        var record = grave.getGravesConfig(); owner = record.getUuid(); worldId = record.getWorldId();
        time = record.getTime(); identity = GraveIdentity.of(record); location = record.getLocation().clone();
    }
    /** Pure retirement: release unused worker capacity, but never claim that native cleanup happened. */
    void retire() {
        retired.set(true); adoptionValid.set(false);
        admission.cancelRetainingFence();
        if (admission.isCancelledBeforeCommit()) outcome = Outcome.CANCELLED;
    }
    private void requireActive() {
        if (retired.get() || admission.isCancelledBeforeCommit() || !plugin.isEnabled()
                || plugin.getStorageManager() != storage || !grave.getGravesConfig().isDestroyed() || !identity.equals(GraveIdentity.of(grave.getGravesConfig())))
            throw new CancellationException("Recreation native actor retired");
    }
    private org.bukkit.persistence.PersistentDataContainer chunkData() { return block.getChunk().getPersistentDataContainer(); }
    private NamespacedKey attemptKey() { return PendingRecreationMarker.tokenKey(plugin, block); }
    private NamespacedKey entityKey() { return new NamespacedKey(plugin, "grave_recreation_attempt"); }
    private boolean ownTags() {
        return attemptId.toString().equals(chunkData().get(attemptKey(), PersistentDataType.STRING))
                && identity.equals(chunkData().get(GraveMarkerCleanup.positionKey(plugin, block), PersistentDataType.STRING));
    }
    /** Called once on the location owner, only after the caller registered its lifecycle attempt. */
    boolean createOnLocation() {
        if (Thread.holdsLock(grave)) throw new IllegalStateException("Native recreation requires an unlocked lifecycle frame");
        if (!started.compareAndSet(false, true)) return false;
        running.set(true);
        try {
            requireActive();
            if (!admission.bindMarker(attemptId, material)) return false;
            if (location.getWorld() == null || !worldId.equals(location.getWorld().getUID())) return false;
            if (type == GraveDisplayType.DISPLAY_ENTITY && plugin.getGraveDisplayEntityHandler() == null) return false;
            block = location.getBlock();
            if (plugin.activeGraveSnapshot().stream().anyMatch(candidate -> candidate != grave && !candidate.isRemove()
                    && !candidate.getGravesConfig().isDestroyed() && identity.equals(GraveIdentity.of(candidate.getGravesConfig())))) return false;
            if (GraveMarkerCleanup.hasConflictingIdentity(plugin, block, identity) || PendingRecreationMarker.hasAttemptTag(plugin, block)) return false;
            previousData = block.getBlockData().clone();
            previousIdentity = chunkData().get(GraveMarkerCleanup.positionKey(plugin, block), PersistentDataType.STRING);
            Material previous = block.getType();
            boolean emptySpace = Set.of(Material.AIR, Material.CAVE_AIR, Material.VOID_AIR, Material.WATER, Material.LAVA).contains(previous);
            var oldState = block.getState();
            boolean tagged = identity.equals(previousIdentity) || oldState instanceof TileState tile
                    && identity.equals(tile.getPersistentDataContainer().get(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING));
            if (!emptySpace && (previous != material || !tagged)) return false;
            if (oldState instanceof Chest chest && !chest.getBlockInventory().isEmpty()) return false;
            requireActive(); ownershipWriteStarted = true;
            chunkData().set(GraveMarkerCleanup.positionKey(plugin, block), PersistentDataType.STRING, identity);
            chunkData().set(attemptKey(), PersistentDataType.STRING, attemptId.toString());
            requireActive();
            if (previous != material) { blockChanged = true; block.setType(material, false); }
            if (block.getType() != material) return false;
            expectedData = block.getBlockData().getAsString();
            requireActive();
            var state = block.getState();
            if (state instanceof Chest chest && !chest.getBlockInventory().isEmpty()) return false;
            if (state instanceof TileState tile) {
                var data = tile.getPersistentDataContainer();
                if (blockChanged && data.getKeys().stream().anyMatch(key -> !key.equals(GraveMarkerCleanup.identityKey(plugin)))) return false;
                tileIdentityAdded = data.get(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING) == null;
                data.set(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING, identity);
                if (!tile.update(false, false)) return false;
            }
            requireActive();
            if (type == GraveDisplayType.DISPLAY_ENTITY) {
                spawnAttempted = true;
                plugin.getGraveDisplayEntityHandler().createRecreationDisplay(grave, captured -> display = captured);
                requireActive();
                if (display == null || display.isDead()) return false;
                displayId = display.getUniqueId();
                display.getPersistentDataContainer().set(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING, identity);
                display.getPersistentDataContainer().set(entityKey(), PersistentDataType.STRING, attemptId.toString());
            }
            var finalState = block.getState();
            if (blockChanged && finalState instanceof Chest chest) {
                expectedName = com.bencodez.simpleapi.messages.MessageAPI.colorize("&3" + grave.getGravesConfig().getPlayerName() + "'s Grave");
                chest.setCustomName(expectedName);
                requireActive(); if (!chest.update(false, false)) return false;
            }
            if (blockChanged && finalState instanceof Skull skull) {
                var player = Bukkit.getOfflinePlayer(owner);
                skull.setOwningPlayer(player);
                requireActive(); if (!skull.update(false, false)) return false;
            }
            if (finalState instanceof Chest chest) expectedName = chest.getCustomName();
            if (finalState instanceof Skull skull) expectedSkullOwner = skull.getOwningPlayer() == null ? null : skull.getOwningPlayer().getUniqueId();
            requireActive(); return created = ownsBlockOnLocation();
        } finally { running.set(false); }
    }
    boolean ownsBlockOnLocation() {
        if (block == null || expectedData == null || !ownTags() || block.getType() != material
                || !expectedData.equals(block.getBlockData().getAsString()) || GraveMarkerCleanup.hasConflictingIdentity(plugin, block, identity)) return false;
        var state = block.getState();
        if (material == Material.CHEST && !(state instanceof Chest) || material == Material.PLAYER_HEAD && !(state instanceof Skull)) return false;
        if (state instanceof TileState tile && !identity.equals(tile.getPersistentDataContainer().get(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING))) return false;
        if (state instanceof Chest chest && (!chest.getBlockInventory().isEmpty() || !Objects.equals(expectedName, chest.getCustomName()))) return false;
        if (state instanceof Skull skull && !Objects.equals(expectedSkullOwner, skull.getOwningPlayer() == null ? null : skull.getOwningPlayer().getUniqueId())) return false;
        return true;
    }
    UUID displayId() { return displayId; }
    ItemDisplay display() { return display; }
    String displayType() { return type.name(); }
    /** Final location-owner evidence; entity evidence is consumed separately without off-owner reads. */
    boolean preparePublicationOnLocation() {
        requireActive();
        if (outcome != Outcome.ACKNOWLEDGED || !hasAdoptionAcknowledgment() || !ownsBlockOnLocation()) return false;
        // The tile/chunk identity is already checked. Avoid generic tag()'s unchecked TileState update.
        block.removeMetadata("Grave", plugin);
        block.setMetadata("Grave", new org.bukkit.metadata.FixedMetadataValue(plugin, grave));
        requireActive();
        if (!ownsBlockOnLocation() || !hasAdoptionAcknowledgment()) return false;
        return true;
    }
    /** Location owner, only after logical publication and the identity fence have transferred to the live grave. */
    void completePublicationOnLocation() {
        if (!grave.getGravesConfig().isDestroyed() && plugin.isLiveGrave(grave) && ownTags()) chunkData().remove(attemptKey());
    }
    /** No native reads: safe to invoke inside the caller's final retirement-versus-commit gate. */
    CompletableFuture<Boolean> commitSql() {
        if (!submitted.compareAndSet(false, true)) return sql.copy();
        try {
            requireActive();
            if (!created || running.get() || expectedData == null || type == GraveDisplayType.DISPLAY_ENTITY && displayId == null) throw new IllegalStateException("Native marker not prepared");
            var record = new GravesConfig(owner, "", new Location(null, location.getBlockX(), location.getBlockY(), location.getBlockZ()), new HashMap<>(), 0, "", time, false, 0,
                    displayId, null, type.name()); record.setWorldId(worldId);
            if (!admission.prepare(record)) throw new IllegalStateException("Final marker metadata unavailable");
            outcome = Outcome.PENDING;
            admission.commit().whenComplete((saved, failure) -> {
                if (admission.isCancelledBeforeCommit()) outcome = Outcome.CANCELLED;
                else outcome = failure != null ? Outcome.HELD : Boolean.TRUE.equals(saved) ? Outcome.ACKNOWLEDGED : Outcome.MISSED;
                if (failure == null) sql.complete(saved); else sql.completeExceptionally(failure);
            });
        } catch (RuntimeException | Error failure) {
            retire();
            if (admission.isCancelledBeforeCommit()) sql.complete(false);
            else { outcome = Outcome.HELD; sql.completeExceptionally(failure); }
        }
        return sql.copy();
    }
    private boolean adoptedElsewhere(ItemDisplay entity) {
        return plugin.activeGraveSnapshot().stream().anyMatch(candidate -> !candidate.isRemove()
                && !candidate.getGravesConfig().isDestroyed() && candidate.getItemDisplay() == entity);
    }
    /** Action and scheduler acknowledgment both belong to the actual entity owner; fallback is uncertainty. */
    private CompletableFuture<Boolean> onDisplay(java.util.function.BooleanSupplier action) {
        var result = new CompletableFuture<Boolean>(); var performed = new CompletableFuture<Boolean>(); var acquired = new AtomicBoolean();
        try {
            plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtEntityWithFallback(display, task -> {
                if (!acquired.compareAndSet(false, true)) return;
                try { performed.complete(action.getAsBoolean()); } catch (RuntimeException | Error failure) { performed.complete(false); }
            }, () -> { if (acquired.compareAndSet(false, true)) performed.complete(false); }).whenComplete((scheduled, failure) -> {
                if (failure != null || scheduled != com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS) {
                    if (acquired.compareAndSet(false, true)) performed.complete(false);
                }
                performed.whenComplete((ok, error) -> result.complete(failure == null
                        && scheduled == com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS && error == null && Boolean.TRUE.equals(ok)));
            });
        } catch (RuntimeException | Error rejected) { result.complete(false); }
        return result;
    }
    private boolean inDisplayFootprint() {
        if (!worldId.equals(display.getWorld().getUID())) return false;
        Location actual = display.getLocation();
        return actual != null && actual.getWorld() != null && worldId.equals(actual.getWorld().getUID())
                && Math.abs(actual.getX() - (location.getBlockX() + .5)) <= .75
                && Math.abs(actual.getY() - (location.getBlockY() + .5)) <= .75
                && Math.abs(actual.getZ() - (location.getBlockZ() + .5)) <= .75;
    }
    /** Entity-owner evidence at this boundary, not an atomic lock against unrelated external mutation. */
    CompletableFuture<Boolean> adoptDisplayOnOwner() {
        if (Thread.holdsLock(grave)) return CompletableFuture.completedFuture(false);
        if (!adoptionRequested.compareAndSet(false, true)) return adoption.copy();
        if (outcome != Outcome.ACKNOWLEDGED || retired.get()) { adoption.complete(false); return adoption.copy(); }
        if (type != GraveDisplayType.DISPLAY_ENTITY) { adoptionValid.set(true); adoption.complete(true); return adoption.copy(); }
        onDisplay(() -> {
            requireActive();
            if (display.isDead() || !display.isValid() || !displayId.equals(display.getUniqueId()) || adoptedElsewhere(display)
                    || !inDisplayFootprint()) return false;
            var data = display.getPersistentDataContainer();
            if (!identity.equals(data.get(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING))
                    || !attemptId.toString().equals(data.get(entityKey(), PersistentDataType.STRING))) return false;
            display.setMetadata("Grave", new org.bukkit.metadata.FixedMetadataValue(plugin, grave));
            requireActive(); adoptionValid.set(true); return true;
        }).whenComplete((accepted, failure) -> {
            boolean ok = failure == null && Boolean.TRUE.equals(accepted) && !retired.get();
            if (!ok) adoptionValid.set(false);
            adoption.complete(ok);
        });
        return adoption.copy();
    }
    /** Pure evidence consumption on the location owner; actual retirement revokes it independently. */
    boolean hasAdoptionAcknowledgment() { return adoptionValid.get() && !retired.get(); }
    CompletableFuture<Boolean> revokeDisplayOnOwner() {
        retire();
        if (Thread.holdsLock(grave) || running.get()) return CompletableFuture.completedFuture(false);
        if (!revocationRequested.compareAndSet(false, true)) return revocation.copy();
        if (display == null) { revocation.complete(!spawnAttempted); return revocation.copy(); }
        onDisplay(() -> {
            if (display.isDead()) return true;
            var data = display.getPersistentDataContainer();
            if (adoptedElsewhere(display) || !attemptId.toString().equals(data.get(entityKey(), PersistentDataType.STRING))
                    || !identity.equals(data.get(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING))) return false;
            boolean own = display.getMetadata("Grave").stream().anyMatch(value -> value.getOwningPlugin() == plugin && value.value() == grave);
            if (own) display.removeMetadata("Grave", plugin);
            return true;
        }).whenComplete((ok, failure) -> revocation.complete(failure == null && Boolean.TRUE.equals(ok)));
        return revocation.copy();
    }
    /** Only CANCELLED or a retired predicate miss authorizes rollback. The caller releases the fence after true. */
    CompletableFuture<Boolean> rollbackOnLocation() {
        if (Thread.holdsLock(grave)) return CompletableFuture.completedFuture(false);
        if (running.get() || !(admission.isCancelledBeforeCommit() || outcome == Outcome.MISSED)) return CompletableFuture.completedFuture(false);
        if (!rollbackRequested.compareAndSet(false, true)) return rollback.copy();
        boolean blockClean;
        try { blockClean = restoreBlockOnLocation(); } catch (RuntimeException | Error failure) { blockClean = false; }
        final boolean restored = blockClean;
        if (display == null) { rollback.complete(restored && !spawnAttempted); return rollback.copy(); }
        onDisplay(() -> {
            if (display.isDead()) return true;
            if (!inDisplayFootprint()) return false;
            var data = display.getPersistentDataContainer();
            String entityAttempt = data.get(entityKey(), PersistentDataType.STRING);
            String logical = data.get(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING);
            if (adoptedElsewhere(display) || entityAttempt != null && !attemptId.toString().equals(entityAttempt)
                    || logical != null && !identity.equals(logical)) return false;
            display.remove(); return display.isDead();
        }).whenComplete((entityClean, failure) -> rollback.complete(restored && failure == null && Boolean.TRUE.equals(entityClean)));
        return rollback.copy();
    }
    private boolean restoreBlockOnLocation() {
        if (!ownershipWriteStarted) return true;
        if (plugin.getStorageManager() != storage || !grave.getGravesConfig().isDestroyed()) return false;
        if (plugin.activeGraveSnapshot().stream().anyMatch(candidate -> candidate != grave && !candidate.isRemove()
                && !candidate.getGravesConfig().isDestroyed() && identity.equals(GraveIdentity.of(candidate.getGravesConfig())))) return false;
        if (block == null || !ownTags()) return false;
        if (blockChanged) {
            if (!ownsBlockOnLocation()) return false;
            var state = block.getState();
            if (state instanceof TileState tile && tile.getPersistentDataContainer().getKeys().stream()
                    .anyMatch(key -> !key.equals(GraveMarkerCleanup.identityKey(plugin)))) return false;
            block.setBlockData(previousData, false);
            if (!previousData.getAsString().equals(block.getBlockData().getAsString())) return false;
        } else if (tileIdentityAdded && block.getState() instanceof TileState tile) {
            if (!identity.equals(tile.getPersistentDataContainer().get(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING))) return false;
            tile.getPersistentDataContainer().remove(GraveMarkerCleanup.identityKey(plugin));
            if (!tile.update(false, false)) return false;
        }
        if (previousIdentity == null) chunkData().remove(GraveMarkerCleanup.positionKey(plugin, block));
        else chunkData().set(GraveMarkerCleanup.positionKey(plugin, block), PersistentDataType.STRING, previousIdentity);
        chunkData().remove(attemptKey()); return true;
    }
}
