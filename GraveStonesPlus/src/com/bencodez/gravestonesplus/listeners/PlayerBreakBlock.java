package com.bencodez.gravestonesplus.listeners;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.advancedcore.api.user.UserManager;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;

// TODO: Auto-generated Javadoc
/**
 * The Class PlayerInteract.
 */
public class PlayerBreakBlock implements Listener {

	/** The plugin. */
	private GraveStonesPlus plugin;

	/**
	 * Instantiates a new player interact.
	 *
	 * @param plugin the plugin
	 */
	public PlayerBreakBlock(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	/**
	 * On player interact.
	 *
	 * @param event the event
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlayerInteract(BlockBreakEvent event) {
		if (event.getPlayer() != null) {
			if (event.getBlock().getType().equals(Material.PLAYER_HEAD)) {
				for (Grave grave : plugin.getGraves()) {
					if (grave.isGrave(event.getBlock())) {
						if (grave.isOwner(event.getPlayer())) {
							AdvancedCoreUser user = UserManager.getInstance().getUser(event.getPlayer());
							user.giveExp(grave.getGravesConfig().getExp());
							for (ItemStack item : grave.getGravesConfig().getItems()) {
								user.giveItem(item);
							}
							user.sendMessage("You broke your grave!");
							plugin.removeGrave(grave);	
						}
						event.getPlayer().sendMessage("Not your grave!");
						return;
					}
				}
			}
		}
	}
}
