package com.bencodez.gravestonesplus.events;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import com.bencodez.gravestonesplus.graves.Grave;

/**
 * Base event for all GraveStonesPlus grave-related events.
 */
public abstract class GraveEvent extends Event {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Grave grave;

	/**
	 * Creates a new grave event.
	 *
	 * @param grave Grave instance
	 */
	public GraveEvent(Grave grave) {
		this.grave = grave;
	}

	/**
	 * Gets the grave associated with this event.
	 *
	 * @return Grave
	 */
	public Grave getGrave() {
		return grave;
	}

	@Override
	public HandlerList getHandlers() {
		return HANDLERS;
	}

	/**
	 * Gets the handler list for this event.
	 *
	 * @return HandlerList
	 */
	public static HandlerList getHandlerList() {
		return HANDLERS;
	}
}