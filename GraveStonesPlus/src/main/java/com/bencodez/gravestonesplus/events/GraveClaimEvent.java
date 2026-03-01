package com.bencodez.gravestonesplus.events;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;

import com.bencodez.gravestonesplus.graves.Grave;

/**
 * Called when a player attempts to claim a grave (receive its items/exp).
 * This event is cancellable.
 */
public class GraveClaimEvent extends GraveEvent implements Cancellable {

	private static final HandlerList HANDLERS = new HandlerList();

	private final Player player;
	private final boolean owner;
	private boolean cancelled;

	/**
	 * Creates a new grave claim event.
	 *
	 * @param grave Grave being claimed
	 * @param player Player attempting to claim
	 * @param owner True if the player is the grave owner
	 */
	public GraveClaimEvent(Grave grave, Player player, boolean owner) {
		super(grave);
		this.player = player;
		this.owner = owner;
	}

	/**
	 * Gets the player attempting to claim the grave.
	 *
	 * @return Player
	 */
	public Player getPlayer() {
		return player;
	}

	/**
	 * Returns whether the player is the owner of the grave.
	 *
	 * @return True if owner
	 */
	public boolean isOwner() {
		return owner;
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