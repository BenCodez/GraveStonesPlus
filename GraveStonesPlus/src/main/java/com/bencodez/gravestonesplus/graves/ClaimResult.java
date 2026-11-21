package com.bencodez.gravestonesplus.graves;

/** Outcome of one claim request. A held recovery must never be automatically replayed. */
public enum ClaimResult {
    /** Authorization, cancellation, retirement or a competing request prevented this payout. */
    DENIED,
    /** Items/XP and overflow were delivered, and the non-replayable completion was acknowledged. */
    COMPLETED,
    /** No payout occurred and this request did not retain a reservation. */
    FAILED,
    /** Reservation, delivery or completion is uncertain; inspect retained recovery evidence. */
    HELD
}
