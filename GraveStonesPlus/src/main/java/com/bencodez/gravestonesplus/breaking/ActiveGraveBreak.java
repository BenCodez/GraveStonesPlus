package com.bencodez.gravestonesplus.breaking;

import java.util.UUID;

import lombok.Getter;
import lombok.Setter;

/**
 * Stores an active timed grave break attempt for a player.
 */
@Getter
@Setter
public class ActiveGraveBreak {

    /**
     * UUID of the player breaking the grave.
     */
    private UUID playerUUID;

    /**
     * Unique grave id.
     */
    private String graveId;

    /**
     * Time in milliseconds when the break attempt started.
     */
    private long startTime;

    /**
     * Time in milliseconds when the player last hit the grave.
     */
    private long lastHitTime;
}