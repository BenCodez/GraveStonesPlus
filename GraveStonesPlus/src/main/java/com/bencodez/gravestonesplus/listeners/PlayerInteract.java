package com.bencodez.gravestonesplus.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.events.GraveInteractEvent;
import com.bencodez.gravestonesplus.events.GraveInteractionType;
import com.bencodez.gravestonesplus.graves.Grave;

/**
 * Handles player interactions with grave blocks.
 */
public class PlayerInteract implements Listener {

	/** Plugin instance. */
	private final GraveStonesPlus plugin;

	/**
	 * Constructor.
	 *
	 * @param plugin Plugin instance
	 */
	public PlayerInteract(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	/**
	 * Handles player interaction events for graves.
	 *
	 * @param event Event
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlayerInteract(PlayerInteractEvent event) {
		Block clicked = event.getClickedBlock();
		if (clicked == null) {
			return;
		}

		Action action = event.getAction();
		Material type = clicked.getType();

		if (action == Action.RIGHT_CLICK_BLOCK) {
			// Fake chest safety: if this is a grave chest, prevent vanilla opening.
			if (type == Material.CHEST) {
				Grave grave = getGraveFromBlock(clicked);
				if (grave != null) {
					GraveInteractEvent ge = new GraveInteractEvent(grave, event.getPlayer(),
							GraveInteractionType.RIGHT_CLICK);
					Bukkit.getPluginManager().callEvent(ge);
					if (ge.isCancelled()) {
						event.setCancelled(true);
						return;
					}

					event.setCancelled(true);
					handleRightClick(event, grave);
				}
				return;
			}

			if (type == Material.PLAYER_HEAD || type == Material.BARRIER) {
				Grave grave = getGraveFromBlock(clicked);
				if (grave == null) {
					return;
				}

				GraveInteractEvent ge = new GraveInteractEvent(grave, event.getPlayer(),
						GraveInteractionType.RIGHT_CLICK);
				Bukkit.getPluginManager().callEvent(ge);
				if (ge.isCancelled()) {
					event.setCancelled(true);
					return;
				}

				handleRightClick(event, grave);
			}
			return;
		}

		if (action == Action.LEFT_CLICK_BLOCK) {
			if (type == Material.BARRIER) {
				Grave grave = getGraveFromBlock(clicked);
				if (grave == null) {
					return;
				}

				GraveInteractEvent ge = new GraveInteractEvent(grave, event.getPlayer(),
						GraveInteractionType.LEFT_CLICK);
				Bukkit.getPluginManager().callEvent(ge);
				if (ge.isCancelled()) {
					event.setCancelled(true);
					return;
				}

				handleLeftClickBarrier(event, grave);
			}
		}
	}

	/**
	 * Attempts to retrieve a Grave from a block's metadata safely.
	 *
	 * @param block Block clicked
	 * @return Grave instance or null if not present/invalid type
	 */
	private Grave getGraveFromBlock(Block block) {
		Object obj = MiscUtils.getInstance().getBlockMeta(block, "Grave");
		if (!(obj instanceof Grave)) {
			return null;
		}
		Grave grave = (Grave) obj;

		// Extra safety: ignore stale/invalid graves
		if (!grave.isValid()) {
			return null;
		}

		return grave;
	}

	private void handleRightClick(PlayerInteractEvent event, Grave grave) {
		long lastClick = grave.getLastClick();
		long now = System.currentTimeMillis();
		grave.setLastClick(now);

		if (now - lastClick <= 500L) {
			return;
		}

		grave.onClick(event.getPlayer());
	}

	private void handleLeftClickBarrier(PlayerInteractEvent event, Grave grave) {
		if (grave.isOwner(event.getPlayer())) {
			grave.claim(event.getPlayer());
			return;
		}

		if (event.getPlayer().hasPermission("GraveStonesPlus.BreakOtherGraves")) {
			if (plugin.getConfigFile().isBreakOtherGravesWithPermission()) {
				grave.claim(event.getPlayer());
				return;
			}
			plugin.debug("Config option disabled to break other graves");
		} else {
			plugin.debug("No permission to break other graves");
		}

		event.getPlayer().sendMessage(plugin.getConfigFile().getFormatNotYourGrave());
	}
}