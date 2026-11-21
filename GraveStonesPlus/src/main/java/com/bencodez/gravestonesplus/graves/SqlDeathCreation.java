package com.bencodez.gravestonesplus.graves;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.block.Skull;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.storage.*;
import com.bencodez.simpleapi.messages.MessageAPI;

/** One bounded death obligation. Native frame ownership is independent of SQL/observer completion. */
final class SqlDeathCreation implements Runnable {
    private enum Phase { PREPARING, ACCEPTED, RUNNING, WAITING, ADOPTING, PUBLISHING, RETIRED, FINISHED }
    private final GraveStonesPlus plugin;
    private final GraveStorageManager storage;
    private final GraveRestoration restoration;
    private final FeatureIO io;
    private final GravesConfig record;
    private final GraveDisplayType type;
    private final OfflinePlayer skullOwner;
    private final ItemStack displayItem;
    private final SqlRecoveryJournal.Reservation sql;
    private final FeatureIO.Actor actor;
    private final Consumer<Grave> success;
    private final NamespacedKey markerKey, identityKey;
    private ItemDisplay nativeDisplay;
    private volatile boolean runtimeMetadata;
    private Phase phase = Phase.PREPARING;
    private volatile boolean retired;
    private boolean metadata, registered;
    private Grave.LimitPreparation limit;
    private CompletableFuture<Boolean> saved;

    SqlDeathCreation(GraveStonesPlus plugin, GravesConfig record, Consumer<Grave> success) {
        this.plugin = plugin; this.record = record; this.success = success;
        storage = plugin.getStorageManager(); io = plugin.getFeatureIO(); restoration = plugin.getGraveRestoration();
        type = GraveDisplayType.valueOf(record.getGraveDisplayType());
        // All configurable profile/capability work is before capture and may reject safely.
        if (type == GraveDisplayType.DISPLAY_ENTITY) {
            if (plugin.getGraveDisplayEntityHandler() == null || !org.bukkit.entity.EntityType.ITEM_DISPLAY.isEnabledByFeature(record.getLocation().getWorld()))
                throw new RejectedExecutionException("Grave display capability unavailable");
        }
        markerKey = type == GraveDisplayType.DISPLAY_ENTITY ? java.util.Objects.requireNonNull(plugin.getKey(), "Grave marker key unavailable") : null;
        identityKey = type == GraveDisplayType.DISPLAY_ENTITY ? GraveMarkerCleanup.identityKey(plugin) : null;
        skullOwner = type == GraveDisplayType.CHEST ? null : Bukkit.getOfflinePlayer(record.getUuid());
        displayItem = type == GraveDisplayType.DISPLAY_ENTITY
                ? new ItemBuilder(Material.PLAYER_HEAD).setSkullOwner(skullOwner).toItemStack().clone() : null;
        if (!plugin.isEnabled() || storage != plugin.getStorageManager()) throw new RejectedExecutionException("Grave capture owner retired");
        actor = io.retainActor(); if (actor == null) throw new RejectedExecutionException("Grave native ownership capacity unavailable");
        SqlRecoveryJournal.Reservation reserved = null;
        try {
            reserved = storage.reserveSqlCreation(record);
            if (reserved == null) throw new RejectedExecutionException("SQL creation capacity unavailable");
            sql = reserved;
            if (!plugin.registerRecreationAttempt(this)) throw new RejectedExecutionException("Grave capture lifecycle retired");
            registered = true;
        } catch (RuntimeException | Error failure) { if (reserved != null) reserved.close(); actor.close(); throw failure; }
    }
    void prepareLimit(Grave previous, org.bukkit.entity.Player recipient) {
        if (previous == null) return;
        limit = previous.prepareDeathLimit(recipient, plugin.getConfigFile().isDropItemsOnGraveRemoval());
        if (limit == null) throw new RejectedExecutionException("Grave limit preparation unavailable");
    }
    synchronized boolean capture(Runnable mutation) {
        if (retired || phase != Phase.PREPARING || !current() || !sql.acceptCreation()) return false;
        // Server death event field/list mutations do not dispatch plugin callbacks.
        mutation.run(); phase = Phase.ACCEPTED; return true;
    }
    void commitLimit() { if (limit != null) limit.commit(); }
    private boolean current() { return !retired && plugin.isEnabled() && plugin.getStorageManager() == storage && plugin.getFeatureIO() == io; }
    private boolean publishable(Grave grave) { return current() && !grave.isRemove() && !record.isDestroyed(); }
    private synchronized void checkpoint(UUID uuid, boolean neverStarted) {
        if (metadata) return;
        if (!sql.prepareCreation(uuid, null, neverStarted)) throw new IllegalStateException("Creation metadata invariant unavailable");
        record.setDisplayUUID(uuid); metadata = true; saved = sql.commit();
    }
    @Override public void run() {
        boolean unused = false, nonexecution = false;
        synchronized (this) {
            retired = true;
            if (phase == Phase.PREPARING) { phase = Phase.RETIRED; unused = true; }
            else if (phase == Phase.ACCEPTED) { phase = Phase.RETIRED; nonexecution = true; }
            else if (phase == Phase.WAITING) { phase = Phase.RETIRED; unused = true; }
        }
        if (limit != null) limit.close();
        if (unused) { sql.close(); if (runtimeMetadata) revokeDisplayThenFinish(); else finish(); }
        if (nonexecution) { checkpoint(null, true); finish(); }
        // RUNNING owns UUID finalization and actor retirement through actual native-frame exit.
    }
    synchronized void callbackReturned() {
        // Wrapper acquisition can fail before native entry (for example restoration retirement).
        if (phase == Phase.ACCEPTED) run();
    }
    void create(Grave grave) {
        synchronized (this) {
            if (phase != Phase.ACCEPTED || !publishable(grave)) { run(); return; }
            phase = Phase.RUNNING;
        }
        boolean nativeComplete = false;
        try {
            ItemDisplay display = null;
            if (type == GraveDisplayType.DISPLAY_ENTITY) {
                Location loc = record.getLocation();
                // The proved fixed factory prefix has no chunk loading/configurable callbacks.
                display = loc.getWorld().spawn(new Location(loc.getWorld(), loc.getBlockX() + .5, loc.getBlockY() + .5, loc.getBlockZ() + .5),
                        ItemDisplay.class, entity -> {
                            checkpoint(entity.getUniqueId(), false); // actual UUID first; consumes the original admission
                            nativeDisplay = entity;
                            // Recovery evidence is not claim publication. Both tags precede any setter
                            // that can retire the generation; a tag failure aborts insertion.
                            var data = entity.getPersistentDataContainer();
                            data.set(identityKey, org.bukkit.persistence.PersistentDataType.STRING, GraveIdentity.of(record));
                            data.set(markerKey, org.bukkit.persistence.PersistentDataType.INTEGER, 1);
                            if (!publishable(grave)) return;
                            entity.setItemStack(displayItem);
                            if (!publishable(grave)) return;
                            entity.setVisibleByDefault(true);
                        });
                if (display == null) return;
            } else checkpoint(null, false);
            if (!publishable(grave)) return;
            // Do not reuse loadChunk/force-load prefix. All callback-capable world work is AFTER checkpoint.
            if (restoration != null) restoration.protectCreation(record);
            if (!publishable(grave)) return;
            var block = record.getLocation().getBlock();
            if (!publishable(grave)) return;
            Material material = switch (type) { case CHEST -> Material.CHEST; case PLAYER_HEAD -> Material.PLAYER_HEAD; case DISPLAY_ENTITY -> Material.BARRIER; };
            block.setType(material);
            if (!publishable(grave)) return;
            var state = block.getState();
            if (state instanceof Chest chest) { chest.setCustomName(MessageAPI.colorize("&3" + record.getPlayerName() + "'s Grave"));
                if (!publishable(grave)) return; chest.update(true); }
            if (state instanceof Skull skull) { skull.setOwningPlayer(skullOwner); if (!publishable(grave)) return; skull.update(); }
            if (!publishable(grave)) return;
            grave.bindCreationDisplay(display); nativeComplete = true;
        } catch (RuntimeException | Error failure) {
            // If the Consumer was never called, the proved factory never reached insertion.
            if (!metadata) checkpoint(null, true);
            plugin.getLogger().severe("Accepted grave creation retained for storage/native reconciliation.");
        } finally {
            synchronized (this) { phase = nativeComplete ? Phase.WAITING : Phase.FINISHED; }
            if (nativeComplete && !retired) publishAfterSql(grave); else finish();
        }
    }
    private void publishAfterSql(Grave grave) {
        saved.whenComplete((ack, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(ack)) { finish(); return; }
            if (nativeDisplay == null) { publishOnLocation(grave); return; }
            adoptDisplay(grave);
        });
    }
    private void adoptDisplay(Grave grave) {
        var acquired = new java.util.concurrent.atomic.AtomicBoolean();
        var performed = new CompletableFuture<Boolean>();
        try {
            plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtEntityWithFallback(nativeDisplay, task -> {
                if (!acquired.compareAndSet(false, true)) return;
                synchronized (SqlDeathCreation.this) {
                    if (phase != Phase.WAITING || !publishable(grave)) { performed.complete(false); return; }
                    phase = Phase.ADOPTING;
                }
                try {
                    var data = nativeDisplay.getPersistentDataContainer();
                    if (!publishable(grave) || !nativeDisplay.isValid() || nativeDisplay.isDead() || !displayFootprint()
                            || !record.getDisplayUUID().equals(nativeDisplay.getUniqueId())
                            || !GraveIdentity.of(record).equals(data.get(identityKey, org.bukkit.persistence.PersistentDataType.STRING))) {
                        performed.complete(false); return;
                    }
                    runtimeMetadata = true;
                    nativeDisplay.setMetadata("Grave", new org.bukkit.metadata.FixedMetadataValue(plugin, grave));
                    if (!publishable(grave)) { nativeDisplay.removeMetadata("Grave", plugin); runtimeMetadata = false; }
                    performed.complete(publishable(grave));
                } catch (RuntimeException | Error failure) { performed.complete(false); }
            }, () -> { if (acquired.compareAndSet(false, true)) performed.complete(false); }).whenComplete((status, failure) -> {
                if (failure != null || status != com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS)
                    if (acquired.compareAndSet(false, true)) performed.complete(false);
                performed.whenComplete((ok, error) -> {
                    synchronized (SqlDeathCreation.this) { if (phase == Phase.ADOPTING) phase = Phase.WAITING; }
                    if (failure == null && status == com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS
                            && error == null && Boolean.TRUE.equals(ok) && publishable(grave)) publishOnLocation(grave);
                    else if (runtimeMetadata) revokeDisplayThenFinish(); else finish();
                });
            });
        } catch (RuntimeException | Error rejected) { if (runtimeMetadata) revokeDisplayThenFinish(); else finish(); }
    }
    private boolean displayFootprint() {
        Location actual = nativeDisplay.getLocation(), expected = record.getLocation();
        return actual != null && actual.getWorld() != null && record.getWorldId().equals(actual.getWorld().getUID())
                && Math.abs(actual.getX() - (expected.getBlockX() + .5)) <= .75
                && Math.abs(actual.getY() - (expected.getBlockY() + .5)) <= .75
                && Math.abs(actual.getZ() - (expected.getBlockZ() + .5)) <= .75;
    }
    private final java.util.concurrent.atomic.AtomicBoolean revocation = new java.util.concurrent.atomic.AtomicBoolean();
    private void revokeDisplayThenFinish() {
        if (!revocation.compareAndSet(false, true)) return;
        var acquired = new java.util.concurrent.atomic.AtomicBoolean();
        var performed = new CompletableFuture<Void>();
        try {
            plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtEntityWithFallback(nativeDisplay, task -> {
                if (!acquired.compareAndSet(false, true)) return;
                try {
                    // Do not erase metadata adopted by another grave wrapper.
                    if (record.getDisplayUUID().equals(nativeDisplay.getUniqueId()) && nativeDisplay.getMetadata("Grave").stream()
                            .anyMatch(value -> value.getOwningPlugin() == plugin && value.value() instanceof Grave owner
                                && owner.getGravesConfig() == record)) nativeDisplay.removeMetadata("Grave", plugin);
                } finally { runtimeMetadata = false; performed.complete(null); }
            }, () -> { if (acquired.compareAndSet(false, true)) performed.complete(null); }).whenComplete((status, failure) -> {
                if (failure != null || status != com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS)
                    if (acquired.compareAndSet(false, true)) performed.complete(null);
                performed.whenComplete((ignored, error) -> finish());
            });
        } catch (RuntimeException | Error rejected) { if (acquired.compareAndSet(false, true)) performed.complete(null); performed.whenComplete((ignored,error)->finish()); }
    }
    private void publishOnLocation(Grave grave) {
            try { plugin.getBukkitScheduler().runTask(plugin, () -> {
                synchronized (SqlDeathCreation.this) {
                    if (phase != Phase.WAITING || !publishable(grave)) { if (runtimeMetadata) revokeDisplayThenFinish(); else finish(); return; }
                    phase = Phase.PUBLISHING;
                }
                try {
                    plugin.publishSqlCreatedGrave(grave);
                    if (!publishable(grave)) return;
                    if (restoration != null) restoration.completeCreation(record);
                    if (!publishable(grave)) return;
                    if (!grave.loadCreationBlockMeta(record.getLocation().getBlock(), () -> publishable(grave))) return;
                    if (publishable(grave)) success.accept(grave);
                } finally { synchronized (SqlDeathCreation.this) { phase = Phase.FINISHED; }
                    if (!publishable(grave) && runtimeMetadata) revokeDisplayThenFinish(); else finish(); }
            }, record.getLocation()); }
            catch (RuntimeException unavailable) { if (runtimeMetadata) revokeDisplayThenFinish(); else finish(); } // SQL is settled; restart owns withheld publication.
    }
    private void finish() {
        actor.close();
        if (registered) plugin.unregisterRecreationAttempt(this);
    }
}
