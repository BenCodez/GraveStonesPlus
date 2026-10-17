package com.bencodez.gravestonesplus.graves;

import java.util.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;
import com.bencodez.gravestonesplus.GraveStonesPlus;

/** Selection and compass validation read identities and locations only. */
public final class GraveNavigation {
    private final GraveStonesPlus plugin;
    private final Map<UUID, String> selected = new java.util.concurrent.ConcurrentHashMap<>();
    public GraveNavigation(GraveStonesPlus plugin) { this.plugin = plugin; }
    public Grave target(Player player) {
        if (loading()) return null;
        String identity = selected.get(player.getUniqueId());
        if (identity == null) return null;
        Grave grave = resolve(identity);
        if (grave == null && pending(identity)) return null;
        if (grave == null || !plugin.canTrack(grave, player)) { selected.remove(player.getUniqueId()); return null; }
        return grave;
    }
    private Grave resolve(String identity) {
        for (Grave grave : plugin.activeGraveSnapshot()) if (identity.equals(GraveIdentity.of(grave.getGravesConfig()))) return grave;
        return null;
    }
    public boolean select(Player player, Grave grave) {
        if (!plugin.canTrack(grave, player) || (selected.size() >= 4096 && !selected.containsKey(player.getUniqueId()))) return false;
        selected.put(player.getUniqueId(), GraveIdentity.of(grave.getGravesConfig())); return true;
    }
    public void forget(Player player) { selected.remove(player.getUniqueId()); }
    public void invalidate(String identity) {
        if (identity == null) return;
        selected.values().removeIf(identity::equals);
        // Inventory changes belong to each player's entity owner. The bounded
        // GraveReminderListener refreshes up to 16 players per five-second pass
        // (and on login), while selection authorization is invalidated above
        // immediately. Do not fan out owner tasks once per grave removal.
    }
    private boolean loading() {
        return plugin.getGraveRestoration() != null && plugin.getGraveRestoration().isLoading()
                || !plugin.getTrustedGraveAccessStore().isReady() || !plugin.getGraveClaimLedger().isReady();
    }
    private boolean pending(String identity) {
        return plugin.getGraveRestoration() != null && plugin.getGraveRestoration().hasPending(identity)
                && !plugin.getGraveRestoration().isConflicted(identity);
    }
    public void refresh(Player player) {
        if (loading()) return;
        target(player);
        if (!player.isOnline()) return;
        NamespacedKey identityKey = new NamespacedKey(plugin, "graveidentity");
        NamespacedKey legacy = new NamespacedKey(plugin, "gravecompass");
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType() != Material.COMPASS || !(item.getItemMeta() instanceof CompassMeta meta)) continue;
            String identity = meta.getPersistentDataContainer().get(identityKey, PersistentDataType.STRING);
            if (identity == null && !meta.getPersistentDataContainer().has(legacy, PersistentDataType.LONG)) continue;
            String legacyIdentity = identity == null ? legacyIdentity(player, meta, legacy) : null;
            Grave grave = identity == null ? legacyTarget(player, meta, legacy) : resolve(identity);
            // Legacy items predate the identity tag. Reconstruct the same stable
            // owner/time/position identity before deciding that a withheld record
            // is stale; restoration may have finished its backend read while the
            // matching record is still awaiting admission.
            if (grave == null && pending(identity == null ? legacyIdentity : identity)) continue;
            if (grave == null || !plugin.canTrack(grave, player)) {
                meta.setLodestone(null); meta.getPersistentDataContainer().remove(identityKey);
                meta.getPersistentDataContainer().remove(legacy); meta.setDisplayName("Inactive grave compass");
            } else {
                meta.setLodestone(grave.getGravesConfig().getLocation()); meta.setLodestoneTracked(false);
                meta.getPersistentDataContainer().set(identityKey, PersistentDataType.STRING, GraveIdentity.of(grave.getGravesConfig()));
                meta.setLore(List.of(grave.getNavigationMessage(player)));
            }
            item.setItemMeta(meta); player.getInventory().setItem(slot, item);
        }
    }
    private Grave legacyTarget(Player player, CompassMeta meta, NamespacedKey key) {
        Long time = meta.getPersistentDataContainer().get(key, PersistentDataType.LONG);
        for (Grave grave : plugin.getGraves(player)) if (time != null && time == grave.getGravesConfig().getTime()
                && meta.getLodestone() != null && meta.getLodestone().equals(grave.getGravesConfig().getLocation())) return grave;
        return null;
    }
    private String legacyIdentity(Player player, CompassMeta meta, NamespacedKey key) {
        Long time = meta.getPersistentDataContainer().get(key, PersistentDataType.LONG);
        Location location = meta.getLodestone();
        if (time == null || location == null || location.getWorld() == null) return null;
        return player.getUniqueId() + "/" + time + "/" + location.getWorld().getUID()
                + "/" + location.getBlockX() + "/" + location.getBlockY() + "/" + location.getBlockZ();
    }
}
