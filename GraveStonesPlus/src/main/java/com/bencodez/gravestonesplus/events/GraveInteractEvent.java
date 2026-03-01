package com.bencodez.gravestonesplus.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

import com.bencodez.gravestonesplus.graves.Grave;

/**
 * Called when a player attempts to interact with a grave marker.
 * This event is cancellable.
 */
public class GraveInteractEvent extends GraveEvent implements Cancellable {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Player player;
	private final GraveInteractionType interactionType;
	private boolean cancelled;

	/**
	 * Creates a new grave interact event.
	 *
	 * @param grave Grave being interacted with
	 * @param player Player interacting
	 * @param interactionType Interaction type
	 */
	public GraveInteractEvent(Grave grave, Player player, GraveInteractionType interactionType) {
		super(grave);
		this.player = player;
		this.interactionType = interactionType;
	}

	/**
	 * Gets the player interacting with the grave.
	 *
	 * @return Player
	 */
	public Player getPlayer() {
		return player;
	}

	/**
	 * Gets the interaction type.
	 *
	 * @return Interaction type
	 */
	public GraveInteractionType getInteractionType() {
		return interactionType;
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