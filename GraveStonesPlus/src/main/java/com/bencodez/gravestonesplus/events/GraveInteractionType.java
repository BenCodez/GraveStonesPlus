package com.bencodez.gravestonesplus.events;

/**
 * Types of interaction attempts with a grave marker.
 */
public enum GraveInteractionType {

	/**
	 * Player left-clicked the grave marker.
	 */
	LEFT_CLICK,

	/**
	 * Player right-clicked the grave marker.
	 */
	RIGHT_CLICK,

	/**
	 * Player attempted to break the grave marker block.
	 */
	BREAK_ATTEMPT
}