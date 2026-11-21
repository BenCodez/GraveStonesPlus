package com.bencodez.gravestonesplus.graves;

import com.bencodez.gravestonesplus.storage.GravesConfig;
import org.bukkit.Location;

/** Existing owner/world/block/death identity, independent of marker UUID or name. */
public final class GraveIdentity {
    private GraveIdentity() { }
    public static String of(GravesConfig grave) {
        Location loc = grave.getLocation();
        if (grave.getUuid() == null || loc == null || grave.getWorldId() == null) return null;
        return grave.getUuid() + "/" + grave.getTime() + "/" + grave.getWorldId()
                + "/" + loc.getBlockX() + "/" + loc.getBlockY() + "/" + loc.getBlockZ();
    }
}
