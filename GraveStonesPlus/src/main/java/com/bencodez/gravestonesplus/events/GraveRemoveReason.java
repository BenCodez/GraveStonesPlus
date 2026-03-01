package com.bencodez.gravestonesplus.events;

/**
 * Reasons a grave can be removed.
 */
public enum GraveRemoveReason {

	/**
	 * The grave was removed because it was claimed.
	 */
	CLAIMED,

	/**
	 * The grave was removed because it expired.
	 */
	EXPIRED,

	/**
	 * The grave was removed by an admin action or command.
	 */
	ADMIN_REMOVED,

	/**
	 * The grave was removed to enforce a configured limit.
	 */
	LIMIT_REACHED,

	/**
	 * The grave was removed manually by plugin logic or configuration.
	 */
	MANUAL_REMOVE,
	
	/**
	 * The grave is no longer valid
	 */
	INVALID;
}