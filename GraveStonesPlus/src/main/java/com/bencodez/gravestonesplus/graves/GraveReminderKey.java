package com.bencodez.gravestonesplus.graves;

import java.util.UUID;

/** Stable identity for one reminder deadline; deadline changes intentionally create a new reminder. */
public final class GraveReminderKey {
    private GraveReminderKey() { }
    public static String of(UUID player, UUID owner, long graveTime, String kind, long deadline) {
        if (player == null || owner == null || kind == null || kind.trim().isEmpty()) throw new IllegalArgumentException("missing reminder identity");
        return player + "/" + owner + "/" + graveTime + "/" + kind + "/" + deadline;
    }
}
