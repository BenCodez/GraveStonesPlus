package com.bencodez.gravestonesplus.listeners;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

import com.bencodez.advancedcore.api.misc.MiscUtils;
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

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onBlockExplode(EntityExplodeEvent event) {
		for (Block b : event.blockList().toArray(new Block[event.blockList().size()])) {
			if (b.getType().equals(Material.PLAYER_HEAD)) {
				Object obj = MiscUtils.getInstance().getBlockMeta(b, "Grave");
				if (obj != null) {
					event.blockList().remove(b);
				}
			}
		}

	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onItemDrop(BlockDropItemEvent event) {
		if (event.getBlock().getType().equals(Material.PLAYER_HEAD)) {
			Object obj = MiscUtils.getInstance().getBlockMeta(event.getBlock(), "Grave");
			if (obj != null) {
				event.setCancelled(true);
				return;
			}
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onWaterMove(BlockFromToEvent event) {
		if (event.getBlock().getType().equals(Material.WATER)) {
			if (event.getToBlock().hasMetadata("Grave")) {
				event.setCancelled(true);
				return;
			}
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onWaterCreate(PlayerBucketEmptyEvent event) {
		if (event.getBlock().getType().equals(Material.PLAYER_HEAD)) {
			if (event.getBlock().hasMetadata("Grave")) {
				event.setCancelled(true);
				return;
			}
		}
	}

	/*
	 * @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true) public
	 * void onBlockBreakItemDrop(ItemSpawnEvent event) { if
	 * (event.getEntity().getItemStack().getType().equals(Material.PLAYER_HEAD)) {
	 * for (Grave grave : plugin.getGraves()) { if
	 * (grave.isGrave(event.getLocation().getBlock())) { event.setCancelled(true);
	 * grave.createSkull(); return; } } } }
	 */

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event) {
		if (event.getPlayer() != null) {
			if (event.getBlock().getType().equals(Material.PLAYER_HEAD)) {

				Object obj = MiscUtils.getInstance().getBlockMeta(event.getBlock(), "Grave");
				if (obj == null) {
					return;
				}
				Grave grave = (Grave) obj;

				if (grave.isOwner(event.getPlayer())) {
					event.setDropItems(false);
					grave.claim(event.getPlayer(), event.getPlayer().getInventory());
					return;
				}

				if (event.getPlayer().hasPermission("GraveStonesPlus.BreakOtherGraves")) {
					if (plugin.getConfigFile().isBreakOtherGravesWithPermission()) {
						event.setDropItems(false);
						grave.claim(event.getPlayer(), event.getPlayer().getInventory());
						return;
					} else {
						plugin.debug("Config option disabled to break other graves");
					}
				} else {
					plugin.debug("No permission to break other graves");
				}

				event.getPlayer().sendMessage(plugin.getConfigFile().getFormatNotYourGrave());
				event.setCancelled(true);
				return;

			}
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPistonBreak(BlockPistonExtendEvent event) {
		for (Block block : event.getBlocks()) {
			if (block.getType().equals(Material.PLAYER_HEAD)) {
				Object obj = MiscUtils.getInstance().getBlockMeta(block, "Grave");
				if (obj == null) {
					return;
				} else {
					event.setCancelled(true);
				}
			}
		}
	}

}
