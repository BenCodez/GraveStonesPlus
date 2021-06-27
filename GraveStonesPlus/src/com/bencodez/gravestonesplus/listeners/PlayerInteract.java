package com.bencodez.gravestonesplus.listeners;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;

// TODO: Auto-generated Javadoc
/**
 * The Class PlayerInteract.
 */
public class PlayerInteract implements Listener {

	/** The plugin. */
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
			if (clicked.getType().equals(Material.PLAYER_HEAD)) {
				for (Grave grave : plugin.getGraves()) {
					if (grave.isGrave(clicked)) {
						grave.onClick(event.getPlayer());
					}
				}
			}
		}
	}
}
