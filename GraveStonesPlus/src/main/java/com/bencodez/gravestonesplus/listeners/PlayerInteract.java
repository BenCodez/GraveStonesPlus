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

// TODO: Auto-generated Javadoc
/**
 * The Class PlayerInteract.
 */
public class PlayerInteract implements Listener {

	/** The plugin. */
	@SuppressWarnings("unused")
	private GraveStonesPlus plugin;

	/**
	 * Instantiates a new player interact.
	 *
	 * @param plugin the plugin
	 */
	public PlayerInteract(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	/**
	 * On player interact.
	 *
	 * @param event the event
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlayerInteract(PlayerInteractEvent event) {
		if (event.getAction().equals(Action.RIGHT_CLICK_BLOCK)) {
			Block clicked = event.getClickedBlock();
			if (clicked.getType().equals(Material.PLAYER_HEAD) || clicked.getType().equals(Material.BARRIER)) {
				Object obj = MiscUtils.getInstance().getBlockMeta(clicked, "Grave");
				if (obj == null) {
					return;
				}
				Grave grave = (Grave) obj;
				long lastClick = grave.getLastClick();
				long cTime = System.currentTimeMillis();
				grave.setLastClick(cTime);
				if (cTime - lastClick > 500) {
					grave.onClick(event.getPlayer());
				}

			}
		}
		if (event.getAction().equals(Action.LEFT_CLICK_BLOCK)) {
			Block clicked = event.getClickedBlock();
			if (clicked.getType().equals(Material.BARRIER)) {
				Object obj = MiscUtils.getInstance().getBlockMeta(clicked, "Grave");
				if (obj == null) {
					return;
				}
				Grave grave = (Grave) obj;
				if (!grave.isValid()) {
					return;
				}

				if (grave.isOwner(event.getPlayer())) {
					grave.claim(event.getPlayer());
					return;
				}

				if (event.getPlayer().hasPermission("GraveStonesPlus.BreakOtherGraves")) {
					if (plugin.getConfigFile().isBreakOtherGravesWithPermission()) {
						grave.claim(event.getPlayer());
						return;
					} else {
						plugin.debug("Config option disabled to break other graves");
					}
				} else {
					plugin.debug("No permission to break other graves");
				}

				event.getPlayer().sendMessage(plugin.getConfigFile().getFormatNotYourGrave());
			}
		}
	}
}
