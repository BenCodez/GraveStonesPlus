package com.bencodez.gravestonesplus.listeners;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GravesConfig;

public class PlayerDeathListener implements Listener {
	private GraveStonesPlus plugin;

	/**
	 * Instantiates a new player interact.
	 *
	 * @param plugin the plugin
	 */
	public PlayerDeathListener(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	/**
	 * On player interact.
	 *
	 * @param event the event
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlayerDeath(PlayerDeathEvent event) {
		Location deathLocation = event.getEntity().getLocation();
		Location emptyBlock = null;
		if (deathLocation.getBlock().isEmpty()) {
			emptyBlock = deathLocation;
		} else {
			emptyBlock = getAirBlock(deathLocation);
		}

		if (emptyBlock == null) {
			plugin.getLogger().info("Failed to find air block");
		}
		if (event.getKeepInventory()) {
			plugin.getLogger().info("Inventory was not dropped");
		}

		Block block = emptyBlock.getBlock();
		block.setType(Material.PLAYER_HEAD);
		if (block.getState() instanceof Skull) {
			Skull skull = (Skull) block.getState();
			skull.setOwningPlayer(event.getEntity());
			skull.update();
		}

		List<ItemStack> drops = new ArrayList<ItemStack>(event.getDrops());

		Grave grave = new Grave(new GravesConfig(event.getEntity().getUniqueId(), event.getEntity().getName(),
				emptyBlock, drops, event.getDroppedExp(), event.getDeathMessage(), System.currentTimeMillis()));
		plugin.addGrave(grave);

		event.setDroppedExp(0);
		event.getDrops().clear();

		event.getEntity().sendMessage("Your grave is at: " + emptyBlock.getBlockX() + ", " + emptyBlock.getBlockY()
				+ ", " + emptyBlock.getBlockZ());

		plugin.getLogger().info("Grave: " + emptyBlock.toString());
	}

	public Location getAirBlock(Location loc) {
		for (int i = loc.getBlockY(); i < loc.getWorld().getMaxHeight(); i++) {
			Block b = loc.getWorld().getBlockAt((int) loc.getX(), i, (int) loc.getZ());
			if (b.isEmpty()) {
				return b.getLocation();
			}
		}
		return null;
	}
}
