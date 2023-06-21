package com.bencodez.gravestonesplus.listeners;

import java.util.HashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import com.bencodez.advancedcore.api.messages.StringParser;
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
		final Location deathLocation = event.getEntity().getLocation();
		if (plugin.getConfigFile().getDisabledWorlds().contains(deathLocation.getWorld().getName())) {
			plugin.debug("Graves in " + deathLocation.getWorld().getName() + " are disabled");
			return;
		}

		if (event.getEntity().hasMetadata("NPC")) {
			plugin.getLogger().info("Not creating grave for NPC");
			return;
		}

		if (!event.getEntity().hasPermission("GraveStonesPlus.AllowGrave")) {
			plugin.getLogger().info("Not creating grave for " + event.getEntity().getName() + ", no permission");
			return;
		}

		if (event.getKeepInventory()) {
			plugin.getLogger().info("Inventory was not dropped, not making grave");
			return;
		}

		if (plugin.getPvpManager() != null) {
			if (!plugin.getPvpManager().canHaveGrave(event.getEntity())) {
				plugin.getLogger().info("Can't create grave, player was in combat");
				return;
			}
		}

		final Player entity = event.getEntity();
		if (plugin.numberOfGraves(entity.getUniqueId()) >= plugin.getConfigFile().getGraveLimit()) {
			Grave oldest = plugin.getOldestGrave(entity.getUniqueId());
			if (oldest != null) {
				oldest.dropItemsOnGround(entity);
				oldest.removeGrave();
				entity.sendMessage(
						StringParser.getInstance().colorize(plugin.getConfigFile().getFormatGraveLimitBreak()));
			}
		}

		PlayerInventory inv = event.getEntity().getInventory();

		final String deathMessage = event.getDeathMessage();
		final int droppedExp = event.getDroppedExp();
		event.setDroppedExp(0);
		event.getDrops().clear();
		// store items with slot number
		HashMap<Integer, ItemStack> itemsWithSlot = new HashMap<Integer, ItemStack>();

		for (int i = 0; i < 36; i++) {
			ItemStack item = inv.getItem(i);
			if (item != null) {
				itemsWithSlot.put(i, item);
			}
		}

		// store items outside of player inventory
		if (inv.getHelmet() != null) {
			itemsWithSlot.put(-1, inv.getHelmet());
		}
		if (inv.getChestplate() != null) {
			itemsWithSlot.put(-2, inv.getChestplate());
		}
		if (inv.getLeggings() != null) {
			itemsWithSlot.put(-3, inv.getLeggings());
		}
		if (inv.getBoots() != null) {
			itemsWithSlot.put(-4, inv.getBoots());
		}
		if (inv.getItemInOffHand() != null) {
			itemsWithSlot.put(-5, inv.getItemInOffHand());
		}
		Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {

			@Override
			public void run() {
				Location emptyBlock = null;
				if (deathLocation.getBlock().isEmpty()
						&& deathLocation.getBlockY() > deathLocation.getWorld().getMinHeight()) {
					emptyBlock = deathLocation;
				} else {
					emptyBlock = getAirBlock(deathLocation);
				}

				if (emptyBlock == null) {
					plugin.getLogger().info("Failed to find air block, can't make grave");
					return;
				}

				Block block = emptyBlock.getBlock();
				block.setType(Material.PLAYER_HEAD);
				if (block.getState() instanceof Skull) {
					Skull skull = (Skull) block.getState();
					skull.setOwningPlayer(event.getEntity());
					skull.update();
				}

				Grave grave = new Grave(plugin, new GravesConfig(entity.getUniqueId(), entity.getName(), emptyBlock,
						itemsWithSlot, droppedExp, deathMessage, System.currentTimeMillis(), false, 0));
				grave.createHologram();
				grave.checkTimeLimit(plugin.getConfigFile().getGraveTimeLimit());
				plugin.addGrave(grave);
				grave.loadBlockMeta(emptyBlock.getBlock());

				HashMap<String, String> placeholders = new HashMap<String, String>();
				placeholders.put("x", "" + emptyBlock.getBlockX());
				placeholders.put("y", "" + emptyBlock.getBlockY());
				placeholders.put("z", "" + emptyBlock.getBlockZ());

				entity.sendMessage(StringParser.getInstance().colorize(StringParser.getInstance()
						.replacePlaceHolder(plugin.getConfigFile().getFormatDeath(), placeholders)));

				plugin.getLogger().info("Grave: " + emptyBlock.toString());
			}
		}, 2);

	}

	public Location getAirBlock(Location loc) {
		int startingY = loc.getBlockY();
		if (startingY < loc.getWorld().getMinHeight()) {
			startingY = loc.getWorld().getMinHeight();
		}
		for (int i = startingY; i < loc.getWorld().getMaxHeight(); i++) {
			Block b = loc.getWorld().getBlockAt((int) loc.getX(), i, (int) loc.getZ());
			if (b.isEmpty() || isReplaceable(b.getType())) {
				return b.getLocation();
			}
		}
		return null;
	}

	public boolean isReplaceable(Material material) {
		switch (material) {
		case TALL_GRASS:
			return true;
		case GRASS:
			return true;
		default:
			return false;
		}
	}
}
