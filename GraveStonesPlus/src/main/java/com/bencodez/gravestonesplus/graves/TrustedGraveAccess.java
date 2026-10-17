package com.bencodez.gravestonesplus.graves;

import java.util.UUID;

/** A bounded, persisted grant for one player and one grave. */
public final class TrustedGraveAccess {
    public enum Kind { TRACK, RECOVER }
    private final UUID graveOwner;
    private final long graveTime;
    private final UUID helper;
    private final Kind kind;
    private final long expiresAt;

    public TrustedGraveAccess(UUID graveOwner, long graveTime, UUID helper, Kind kind, long expiresAt) {
        this.graveOwner = graveOwner;
        this.graveTime = graveTime;
        this.helper = helper;
        this.kind = kind;
        this.expiresAt = expiresAt;
    }
    public UUID getGraveOwner() { return graveOwner; }
    public long getGraveTime() { return graveTime; }
    public UUID getHelper() { return helper; }
    public Kind getKind() { return kind; }
    public long getExpiresAt() { return expiresAt; }
    public boolean isActive(long now) { return expiresAt > now; }
}
