package com.bencodez.gravestonesplus.graves;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustedGraveAccessTest {
    @Test void trackingAndRecoveryAreDistinctAndExpiryIsBoundedByCaller() {
        UUID owner = UUID.randomUUID(), helper = UUID.randomUUID();
        TrustedGraveAccess track = new TrustedGraveAccess(owner, 10L, helper, TrustedGraveAccess.Kind.TRACK, 100L);
        TrustedGraveAccess recover = new TrustedGraveAccess(owner, 10L, helper, TrustedGraveAccess.Kind.RECOVER, 100L);
        assertNotEquals(track.getKind(), recover.getKind());
        assertTrue(track.isActive(99L));
        assertFalse(track.isActive(100L));
    }

    @Test void expiredAndRevokedKindsCannotBeConfused() {
        UUID owner = UUID.randomUUID(), helper = UUID.randomUUID();
        TrustedGraveAccess track = new TrustedGraveAccess(owner, 1L, helper, TrustedGraveAccess.Kind.TRACK, 50L);
        TrustedGraveAccess recover = new TrustedGraveAccess(owner, 1L, helper, TrustedGraveAccess.Kind.RECOVER, 50L);
        assertTrue(track.isActive(49L));
        assertTrue(recover.isActive(49L));
        assertNotEquals(track.getKind(), recover.getKind());
        assertFalse(track.isActive(50L));
    }

    @Test void reminderIdentityIncludesDeadlineAndRejectsMissingActors() {
        UUID player = UUID.randomUUID(), owner = UUID.randomUUID();
        assertNotEquals(GraveReminderKey.of(player, owner, 4L, "expiry", 10L), GraveReminderKey.of(player, owner, 4L, "expiry", 11L));
        assertThrows(IllegalArgumentException.class, () -> GraveReminderKey.of(null, owner, 4L, "expiry", 10L));
        assertThrows(IllegalArgumentException.class, () -> GraveReminderKey.of(player, owner, 4L, "", 10L));
    }

    @Test void nonpositiveAndElapsedGrantsAreInactive() {
        UUID owner = UUID.randomUUID(), helper = UUID.randomUUID();
        assertFalse(new TrustedGraveAccess(owner, 2L, helper, TrustedGraveAccess.Kind.TRACK, 0L).isActive(Long.MAX_VALUE));
        assertFalse(new TrustedGraveAccess(owner, 2L, helper, TrustedGraveAccess.Kind.RECOVER, 1L).isActive(1L));
    }
}
