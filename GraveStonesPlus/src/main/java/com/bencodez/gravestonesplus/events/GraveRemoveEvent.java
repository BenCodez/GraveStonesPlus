package com.bencodez.gravestonesplus.events;

import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

import com.bencodez.gravestonesplus.graves.Grave;

/**
 * Called when a grave is about to be removed.
 * This event is cancellable.
 */
public class GraveRemoveEvent extends GraveEvent implements Cancellable {

	private static final HandlerList HANDLERS = new HandlerList();

	private final GraveRemoveReason reason;
	private boolean cancelled;

	/**
	 * Creates a new grave remove event.
	 *
	 * @param grave Grave being removed
	 * @param reason Removal reason
	 */
	public GraveRemoveEvent(Grave grave, GraveRemoveReason reason) {
		super(grave);
		this.reason = reason;
	}

	/**
	 * Gets the removal reason.
	 *
	 * @return Reason
	 */
	public GraveRemoveReason getReason() {
		return reason;
	}

	@Override
	public boolean isCancelled() {
		return cancelled;
	}

	@Override
	public void setCancelled(boolean cancel) {
		this.cancelled = cancel;
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