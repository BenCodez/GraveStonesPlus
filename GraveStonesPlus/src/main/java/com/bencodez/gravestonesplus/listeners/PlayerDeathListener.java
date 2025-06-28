package com.bencodez.gravestonesplus.listeners;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GravesConfig;
import com.bencodez.gravestonesplus.nbt.NBTRule;
import com.bencodez.simpleapi.messages.MessageAPI;

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

	private final HashMap<UUID, HashMap<Integer, ItemStack>> deathItems = new HashMap<UUID, HashMap<Integer, ItemStack>>();

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerDeath(PlayerDeathEvent event) {
		Player player = event.getEntity();
		Location deathLocation = player.getLocation();

		// Synchronous checks with original log messages
		if (plugin.getConfigFile().getDisabledWorlds().contains(deathLocation.getWorld().getName())) {
			plugin.getLogger().info("Graves in " + deathLocation.getWorld().getName() + " are disabled");
			return;
		}
		if (player.hasMetadata("NPC")) {
			plugin.getLogger().info("Not creating grave for NPC");
			return;
		}
		if (!player.hasPermission("GraveStonesPlus.AllowGrave")) {
			plugin.getLogger().info("Not creating grave for " + player.getName() + ", no permission");
			return;
		}
		if (event.getKeepInventory()) {
			plugin.getLogger().info("Inventory was not dropped, not making grave");
			return;
		}
		if (plugin.getPvpManager() != null && !plugin.getPvpManager().canHaveGrave(player)) {
			plugin.getLogger().info("Can't create grave, player was in combat");
			return;
		}
		if (isInventoryEmpty(player.getInventory()) && !plugin.getConfigFile().isCreateGraveForEmptyInventories()) {
			plugin.getLogger().info("Not creating grave, player has an empty inventory");
			return;
		}

		// Handle grave limit removal sync
		if (plugin.numberOfGraves(player.getUniqueId()) >= plugin.getConfigFile().getGraveLimit()) {
			Grave oldest = plugin.getOldestGrave(player.getUniqueId());
			if (oldest != null) {
				if (plugin.getConfigFile().isDropItemsOnGraveRemoval()) {
					oldest.dropItemsOnGround(player);
				}
				oldest.removeGrave();
				player.sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatGraveLimitBreak()));
			}
		}

		// Prepare data for async processing
		final String deathMessage = event.getDeathMessage();
		final int droppedExp = plugin.getConfigFile().isKeepAllExp() ? getTotalExperience(player)
				: event.getDroppedExp();
		event.setDroppedExp(0);
		event.getDrops().clear();

		// Async processing
		plugin.getBukkitScheduler().runTaskAsynchronously(plugin, () -> {
			HashMap<Integer, ItemStack> itemsWithSlot = new HashMap<>();
			HashMap<Integer, ItemStack> keepItems = new HashMap<>();

			// Placeholder setup
			HashMap<String, String> placeholders = new HashMap<>();
			placeholders.put("player", player.getName());
			placeholders.put("displayname", player.getDisplayName());

			BiConsumer<Integer, ItemStack> processItem = (idx, item) -> {
				if (item == null || item.getType().isAir())
					return;
				if (plugin.getNbtConfigManager() != null
						&& doesItemMatchAnyRule(item, plugin.getNbtConfigManager().getIgnoreNbtRules()))
					return;
				if (plugin.getSlimefun() != null && plugin.getSlimefun().isSoulBoundItem(item))
					return;
				String loreToMatch = com.bencodez.advancedcore.api.messages.PlaceholderUtils
						.replacePlaceHolder(plugin.getConfigFile().getKeepItemsWithLore(), placeholders);
				if (keepItemsWithMatchingLore(item, loreToMatch)) {
					keepItems.put(idx, item);
					return;
				}
				if (plugin.getNbtConfigManager() != null
						&& doesItemMatchAnyRule(item, plugin.getNbtConfigManager().getKeepNbtRules())) {
					keepItems.put(idx, item);
					return;
				}
				if (plugin.getNbtConfigManager() != null
						&& doesItemMatchAnyRule(item, plugin.getNbtConfigManager().getGraveNbtRules())) {
					itemsWithSlot.put(idx, item);
					return;
				}
				if (hasCurseOfVanishing(item))
					return;
				itemsWithSlot.put(idx, item);
			};

			// Process inventory
			PlayerInventory inv = player.getInventory();
			for (int i = 0; i < 36; i++)
				processItem.accept(i, inv.getItem(i));
			processItem.accept(-1, inv.getHelmet());
			processItem.accept(-2, inv.getChestplate());
			processItem.accept(-3, inv.getLeggings());
			processItem.accept(-4, inv.getBoots());
			processItem.accept(-5, inv.getItemInOffHand());

			if (!keepItems.isEmpty())
				deathItems.put(player.getUniqueId(), keepItems);

			// Find air block sync
			Location airBlock = getAirBlock(deathLocation);
			if (airBlock == null) {
				plugin.getLogger().info("Failed to find air block, can't make grave");
				return;
			}

			// Schedule sync task for grave creation
			plugin.getBukkitScheduler().runTask(plugin, () -> {
				Grave grave = new Grave(plugin, new GravesConfig(player.getUniqueId(), player.getName(), airBlock,
						itemsWithSlot, droppedExp, deathMessage, System.currentTimeMillis(), false, 0, null, null));
				grave.loadChunk(false);

				if (!plugin.isUsingDisplayEntities()) {
					Block block = airBlock.getBlock();
					block.setType(Material.PLAYER_HEAD);
					if (block.getState() instanceof Skull) {
						Skull skull = (Skull) block.getState();
						skull.setOwningPlayer(player);
						skull.update();
					}
				}

				grave.createHologram();
				grave.checkTimeLimit(plugin.getConfigFile().getGraveTimeLimit());
				plugin.addGrave(grave);
				grave.loadBlockMeta(airBlock.getBlock());
				if (plugin.isUsingDisplayEntities()) {
					grave.createSkull();
				}

				HashMap<String, String> msgPlaceholders = new HashMap<>();
				msgPlaceholders.put("x", String.valueOf(airBlock.getBlockX()));
				msgPlaceholders.put("y", String.valueOf(airBlock.getBlockY()));
				msgPlaceholders.put("z", String.valueOf(airBlock.getBlockZ()));
				String msg = MessageAPI.colorize(com.bencodez.advancedcore.api.messages.PlaceholderUtils
						.replacePlaceHolder(plugin.getConfigFile().getFormatDeath(), msgPlaceholders));
				if (!msg.isEmpty())
					player.sendMessage(msg);
				plugin.getLogger().info("Grave at: " + airBlock);
			});
		});
	}

	private boolean doesItemMatchAnyRule(ItemStack item, List<NBTRule> keepNbtRules) {
		if (plugin.isNbtAPIHooked()) {
			return new PlayerDeathNBTHandle().doesItemMatchAnyRule(plugin, item, keepNbtRules);
		}
		return false;
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		UUID uuid = player.getUniqueId();
		if (deathItems.containsKey(uuid)) {
			HashMap<Integer, ItemStack> items = deathItems.remove(uuid);
			plugin.getBukkitScheduler().runTask(plugin, () -> {
				PlayerInventory inv = player.getInventory();
				items.forEach((slot, item) -> {
					if (item == null || item.getType().isAir())
						return;
					if (slot >= 0) {
						if (inv.getItem(slot) == null)
							inv.setItem(slot, item);
						else
							inv.addItem(item);
					} else {
						switch (slot) {
						case -1:
							if (isSlotAvailable(inv.getHelmet()))
								inv.setHelmet(item);
							else
								inv.addItem(item);
							break;
						case -2:
							if (isSlotAvailable(inv.getChestplate()))
								inv.setChestplate(item);
							else
								inv.addItem(item);
							break;
						case -3:
							if (isSlotAvailable(inv.getLeggings()))
								inv.setLeggings(item);
							else
								inv.addItem(item);
							break;
						case -4:
							if (isSlotAvailable(inv.getBoots()))
								inv.setBoots(item);
							else
								inv.addItem(item);
							break;
						case -5:
							if (isSlotAvailable(inv.getItemInOffHand()))
								inv.setItemInOffHand(item);
							else
								inv.addItem(item);
							break;
						}
					}
				});
			});
		}

		if (plugin.getConfigFile().isGiveCompassOnRespawn()) {
			plugin.getBukkitScheduler().runTaskLaterAsynchronously(plugin, () -> {
				Grave grave = plugin.getLatestGrave(player);
				if (grave != null) {
					plugin.getBukkitScheduler().runTask(plugin, () -> grave.giveCompass(player));
				}
			}, 5L);
		}
	}

	/**
	 * Calculates a player's total experience as an integer. This sums up the XP for
	 * each completed level plus the XP in the current level.
	 */
	private int getTotalExperience(Player player) {
		int level = player.getLevel();
		int exp = 0;

		// Sum XP required for each full level they've completed
		for (int i = 0; i < level; i++) {
			exp += getExpForLevel(i);
		}

		// Add the XP they've earned toward the next level
		exp += Math.round(player.getExp() * player.getExpToLevel());

		return exp;
	}

	/**
	 * Returns the XP required to go from level n to n+1.
	 */
	private int getExpForLevel(int n) {
		if (n <= 15) {
			return 2 * n + 7;
		} else if (n <= 30) {
			return 5 * n - 38;
		} else {
			return 9 * n - 158;
		}
	}

	private boolean isInventoryEmpty(PlayerInventory inventory) {
		// Check main inventory slots
		for (ItemStack item : inventory.getContents()) {
			if (item != null && !item.getType().isAir()) {
				return false;
			}
		}
		// Check armor slots
		for (ItemStack item : inventory.getArmorContents()) {
			if (item != null && !item.getType().isAir()) {
				return false;
			}
		}
		// Check offhand slot
		ItemStack offHandItem = inventory.getItemInOffHand(); // Corrected method call from IgetItemInOffHand()
		if (offHandItem != null && !offHandItem.getType().isAir()) {
			return false;
		}
		return true;
	}

	public boolean isSlotAvailable(ItemStack slot) {
		return slot == null || slot.getType().isAir();
	}

	public boolean keepItemsWithMatchingLore(ItemStack item, String text) {
		if (item == null || item.getType().isAir() || text == null || text.isEmpty()) {
			return false;
		}
		if (item.hasItemMeta()) {
			ItemMeta meta = item.getItemMeta();
			if (meta.hasLore()) {
				// Iterate through each line of the item's Lore and compare with the provided
				// text
				for (String loreLine : meta.getLore()) {
					if (loreLine.equalsIgnoreCase(text)) { // Use equalsIgnoreCase for case-insensitive comparison
						return true;
					}
				}
			}
		}
		return false;
	}

	public boolean hasCurseOfVanishing(ItemStack item) {
		if (item != null && item.hasItemMeta()) {
			return item.getItemMeta().getEnchants().containsKey(Enchantment.VANISHING_CURSE);
		}
		return false;
	}

	public Location getAirBlock(Location loc) {
		int startingY = loc.getBlockY();
		boolean reverse = false;
		if (startingY < loc.getWorld().getMinHeight()) {
			startingY = loc.getWorld().getMinHeight();
		}
		if (startingY > loc.getWorld().getMaxHeight()) {
			reverse = true;
			startingY = loc.getWorld().getMaxHeight();
		}
		if (!reverse) {
			for (int i = startingY; i < loc.getWorld().getMaxHeight(); i++) {
				Block b = loc.getWorld().getBlockAt((int) loc.getX(), i, (int) loc.getZ());
				if (b.isEmpty() || isReplaceable(b.getType())) {
					return b.getLocation();
				}
			}
		} else {
			for (int i = startingY - 1; i > loc.getWorld().getMinHeight(); i--) {
				Block b = loc.getWorld().getBlockAt((int) loc.getX(), i, (int) loc.getZ());
				if (b.isEmpty() || isReplaceable(b.getType())) {
					return b.getLocation();
				}
			}
		}
		return null;
	}

	public boolean isReplaceable(Material material) {
		switch (material.toString()) {
		case "TALL_GRASS":
		case "GRASS":
		case "SHORT_GRASS":
		case "FERN":
		case "LARGE_FERN":
		case "SNOW":
			return true;
		default:
			return false;
		}
	}
}