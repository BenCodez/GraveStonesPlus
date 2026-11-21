package com.bencodez.gravestonesplus.listeners;
import org.bukkit.event.*;
import org.bukkit.event.world.ChunkLoadEvent;
import com.bencodez.gravestonesplus.GraveStonesPlus;
/** Per-chunk persisted cleanup and withheld startup reconciliation, without forced loading. */
public final class GraveCleanupListener implements Listener {
    private final GraveStonesPlus plugin;
    public GraveCleanupListener(GraveStonesPlus plugin) { this.plugin = plugin; }
    @EventHandler public void onChunkLoad(ChunkLoadEvent event) {
        if (plugin.getMarkerCleanup() != null) plugin.getMarkerCleanup().onChunk(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
        if (plugin.getGraveRestoration() != null) plugin.getGraveRestoration().onChunk(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ());
    }
}
