package com.bencodez.gravestonesplus.listeners;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.gravestonesplus.GraveStonesPlus;
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
			/*
			 * Fake chest safety: if this is a grave chest, prevent vanilla opening. We only
			 * cancel when it is actually a grave, so normal chests still work.
			 */
			if (type == Material.CHEST) {
				Grave grave = getGraveFromBlock(clicked);
				if (grave != null) {
					event.setCancelled(true);
					handleRightClick(event, grave);
				}
				return;
			}

			/*
			 * Existing types: player head / barrier.
			 */
			if (type == Material.PLAYER_HEAD || type == Material.BARRIER) {
				Grave grave = getGraveFromBlock(clicked);
				if (grave == null) {
					return;
				}
				handleRightClick(event, grave);
			}
			return;
		}

		if (action == Action.LEFT_CLICK_BLOCK) {
			/*
			 * Keep left-click behavior limited to your existing barrier logic. (You can add
			 * CHEST here too if you want left-click to claim for chest mode.)
			 */
			if (type == Material.BARRIER) {
				Grave grave = getGraveFromBlock(clicked);
				if (grave == null) {
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

		/*
		 * Extra safety: ignore stale/invalid graves.
		 */
		if (!grave.isValid()) {
			return null;
		}

		return grave;
	}

	/**
	 * Handles right-click interactions for a grave.
	 *
	 * @param event PlayerInteractEvent
	 * @param grave Grave instance
	 */
	private void handleRightClick(PlayerInteractEvent event, Grave grave) {
		/*
		 * Extra safety: throttle spam/double fire from client or plugin interactions.
		 */
		long lastClick = grave.getLastClick();
		long now = System.currentTimeMillis();
		grave.setLastClick(now);

		if (now - lastClick <= 500L) {
			return;
		}

		grave.onClick(event.getPlayer());
	}

	/**
	 * Handles left-click interactions on barrier graves (claim/break behavior).
	 *
	 * @param event PlayerInteractEvent
	 * @param grave Grave instance
	 */
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