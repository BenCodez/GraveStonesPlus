package com.bencodez.gravestonesplus.graves;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.persistence.PersistentDataType;
import com.bencodez.gravestonesplus.GraveStonesPlus;

/** Exact native-attempt protection only. Every block/PDC read belongs to the event/location owner. */
public final class PendingRecreationMarker {
    private PendingRecreationMarker() { }
    public static NamespacedKey tokenKey(GraveStonesPlus plugin, Block block) {
        return new NamespacedKey(plugin, "grave_recreation_" + (block.getX() & 15) + "_" + block.getY() + "_" + (block.getZ() & 15));
    }
    public static boolean hasAttemptTag(GraveStonesPlus plugin, Block block) {
        return block != null && block.getChunk() != null
                && block.getChunk().getPersistentDataContainer().get(tokenKey(plugin, block), PersistentDataType.STRING) != null;
    }
    public static boolean protects(GraveStonesPlus plugin, Block block) {
        return block != null && token(plugin, block, block.getType()) != null;
    }
    /** Expected material is separate so a deferred callback can recognize a provisional AIR gap without repairing it. */
    public static UUID token(GraveStonesPlus plugin, Block block, Material expected) {
        if (block == null || expected == null) return null;
        var storage = plugin.getStorageManager();
        if (storage == null || !storage.hasPendingRecreationMarkers()) return null;
        var data = block.getChunk().getPersistentDataContainer();
        String identity = data.get(GraveMarkerCleanup.positionKey(plugin, block), PersistentDataType.STRING);
        if (identity == null) return null;
        var pending = storage.pendingRecreationMarker(identity);
        if (pending == null || pending.material() != expected || !pending.world().equals(block.getWorld().getUID())
                || pending.x() != block.getX() || pending.y() != block.getY() || pending.z() != block.getZ()) return null;
        String token = data.get(tokenKey(plugin, block), PersistentDataType.STRING);
        if (!pending.token().toString().equals(token) || GraveMarkerCleanup.hasConflictingIdentity(plugin, block, identity)) return null;
        return pending.token();
    }
}
