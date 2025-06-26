package com.bencodez.gravestonesplus.listeners;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GravesConfig;
import com.bencodez.gravestonesplus.nbt.NBTRule;
import com.bencodez.simpleapi.messages.MessageAPI;
import de.tr7zw.nbtapi.NBTCompound;
import de.tr7zw.nbtapi.NBTItem;
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

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

public class PlayerDeathListener implements Listener {
	private final GraveStonesPlus plugin;

	/**
	 * Instantiates a new player interact.
	 *
	 * @param plugin the plugin
	 */
	public PlayerDeathListener(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	private final HashMap<UUID, HashMap<Integer, ItemStack>> deathItems = new HashMap<>();

	/**
	 * Generic NBT rule matching method.
	 * Checks if the given item matches any rule in the provided list of NBT rules.
	 *
	 * @param item The item to check.
	 * @param rules The list of rules to match against.
	 * @return True if the item matches any rule; otherwise, false.
	 */
	private boolean doesItemMatchAnyRule(ItemStack item, List<NBTRule> rules) {
		if (item == null || item.getType().isAir() || rules == null || rules.isEmpty()) {
			return false;
		}

		NBTItem nbtItem = new NBTItem(item);
		if (!nbtItem.hasNBTData()) {
			return false;
		}

		for (NBTRule rule : rules) {
			String path = rule.getPath();
			String type = rule.getType();
			Object expectedValue = rule.getValue();

			String[] pathParts = path.split("\\.");
			NBTCompound currentCompound = nbtItem;

			for (int i = 0; i < pathParts.length - 1; i++) {
				String part = pathParts[i];
				if (part.matches("\\d+")) {
					plugin.getLogger().warning("NBT rule '" + rule.getName() + "' path '" + path + "' contains list index which is not fully supported by this direct implementation.");
					currentCompound = null;
					break;
				}
				if (currentCompound.hasTag(part)) {
					currentCompound = currentCompound.getCompound(part);
				} else {
					currentCompound = null;
					break;
				}
			}

			if (currentCompound == null) {
				continue;
			}

			String finalTag = pathParts[pathParts.length - 1];

			if (!currentCompound.hasTag(finalTag)) {
				continue;
			}

			if ("EXISTS".equalsIgnoreCase(type)) {
				return true;
			}

			Object actualValue;
			try {
				switch (type) {
					case "STRING": actualValue = currentCompound.getString(finalTag); break;
					case "INTEGER": actualValue = currentCompound.getInteger(finalTag); break;
					case "BOOLEAN": actualValue = currentCompound.getBoolean(finalTag); break;
					case "DOUBLE": actualValue = currentCompound.getDouble(finalTag); break;
					case "BYTE": actualValue = currentCompound.getByte(finalTag); break;
					case "SHORT": actualValue = currentCompound.getShort(finalTag); break;
					case "LONG": actualValue = currentCompound.getLong(finalTag); break;
					case "FLOAT": actualValue = currentCompound.getFloat(finalTag); break;
					default:
						plugin.getLogger().warning("NBT rule '" + rule.getName() + "' has unsupported type: " + type);
						continue;
				}
			} catch (Exception e) {
				plugin.getLogger().warning("Error reading NBT for rule '" + rule.getName() + "' (Path: " + path + ", Type: " + type + "): " + e.getMessage());
				continue;
			}

			if (actualValue instanceof Number && expectedValue instanceof Number) {
				if (((Number) actualValue).doubleValue() == ((Number) expectedValue).doubleValue()) {
					return true;
				}
			} else if (actualValue != null && actualValue.equals(expectedValue)) {
				return true;
			}
		}
		return false;
	}


	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerDeath(PlayerDeathEvent event) {
		final Player entity = event.getEntity();
		final Location deathLocation = entity.getLocation();

		// --- Start of Synchronous Pre-Checks (Run on Main Thread) ---

		if (plugin.getConfigFile().getDisabledWorlds().contains(deathLocation.getWorld().getName())) {
			plugin.debug("Graves in " + deathLocation.getWorld().getName() + " are disabled");
			return;
		}

		if (entity.hasMetadata("NPC")) {
			plugin.getLogger().info("Not creating grave for NPC");
			return;
		}

		if (!entity.hasPermission("GraveStonesPlus.AllowGrave")) {
			plugin.getLogger().info("Not creating grave for " + entity.getName() + ", no permission");
			return;
		}

		if (event.getKeepInventory()) {
			plugin.getLogger().info("Inventory was not dropped, not making grave");
			return;
		}

		if (plugin.getPvpManager() != null && !plugin.getPvpManager().canHaveGrave(entity)) {
			plugin.getLogger().info("Can't create grave, player was in combat");
			return;
		}

		if (isInventoryEmpty(entity.getInventory()) && !plugin.getConfigFile().isCreateGraveForEmptyInventories()) {
			plugin.getLogger().info("Not creating grave, player has an empty inventory");
			return;
		}

		// Find a suitable location for the grave before going async. Abort if none found.
		final Location graveLocation = getAirBlock(deathLocation);
		if (graveLocation == null) {
			plugin.getLogger().info("Failed to find air block, can't make grave for " + entity.getName());
			return;
		}

		// --- Data Preparation for Asynchronous Task ---

		final String deathMessage = event.getDeathMessage();
		final int level = entity.getLevel();
		final float expProgress = entity.getExp();

		// Create a thread-safe snapshot of the player's inventory
		final HashMap<Integer, ItemStack> inventorySnapshot = new HashMap<>();
		PlayerInventory inv = entity.getInventory();
		for (int i = 0; i < 36; i++) {
			if (inv.getItem(i) != null) inventorySnapshot.put(i, inv.getItem(i).clone());
		}
		if (inv.getHelmet() != null) inventorySnapshot.put(-1, inv.getHelmet().clone());
		if (inv.getChestplate() != null) inventorySnapshot.put(-2, inv.getChestplate().clone());
		if (inv.getLeggings() != null) inventorySnapshot.put(-3, inv.getLeggings().clone());
		if (inv.getBoots() != null) inventorySnapshot.put(-4, inv.getBoots().clone());
		if (inv.getItemInOffHand() != null) inventorySnapshot.put(-5, inv.getItemInOffHand().clone());

		// Clear drops and experience from the event on the main thread
		event.getDrops().clear();
		event.setDroppedExp(0);

		// --- Start of Asynchronous Task ---
		plugin.getBukkitScheduler().runTaskAsynchronously(plugin, () -> {
			// All logic inside this block runs on a separate thread

			// --- Item Processing (Async) ---
			final HashMap<String, String> playerPlaceholders = new HashMap<>();
			playerPlaceholders.put("player", entity.getName());
			playerPlaceholders.put("displayname", entity.getDisplayName());

			final HashMap<Integer, ItemStack> itemsForGrave = new HashMap<>();
			final HashMap<Integer, ItemStack> itemsToKeep = new HashMap<>();

			final String loreToMatch = com.bencodez.advancedcore.api.messages.PlaceholderUtils.replacePlaceHolder(
					plugin.getConfigFile().getKeepItemsWithLore(),
					playerPlaceholders);

			BiConsumer<Integer, ItemStack> processItem = (idx, item) -> {
				if (item != null && !item.getType().isAir()) {
					if (plugin.nbtConfigManager != null && doesItemMatchAnyRule(item, plugin.nbtConfigManager.getIgnoreNbtRules())) {
						return;
					}
					if (plugin.getSlimefun() != null && plugin.getSlimefun().isSoulBoundItem(item)) {
						return;
					}
					if (keepItemsWithMatchingLore(item, loreToMatch)) {
						itemsToKeep.put(idx, item);
						return;
					}
					if (plugin.nbtConfigManager != null && doesItemMatchAnyRule(item, plugin.nbtConfigManager.getKeepNbtRules())) {
						itemsToKeep.put(idx, item);
						return;
					}
					if (plugin.nbtConfigManager != null && doesItemMatchAnyRule(item, plugin.nbtConfigManager.getGraveNbtRules())) {
						itemsForGrave.put(idx, item);
						return;
					}
					if (hasCurseOfVanishing(item)) {
						return;
					}
					itemsForGrave.put(idx, item);
				}
			};

			inventorySnapshot.forEach(processItem);

			final int experienceToDrop = plugin.getConfigFile().isKeepAllExp() ? getTotalExperience(level, expProgress) : event.getDroppedExp();

			// --- Callback to Main Thread for World Interaction ---
			plugin.getBukkitScheduler().runTask(plugin, () -> {
				// All logic inside this block runs back on the main server thread

				if (!itemsToKeep.isEmpty()) {
					deathItems.put(entity.getUniqueId(), itemsToKeep);
				}

				// Handle grave limit and oldest grave removal
				if (plugin.numberOfGraves(entity.getUniqueId()) >= plugin.getConfigFile().getGraveLimit()) {
					Grave oldest = plugin.getOldestGrave(entity.getUniqueId());
					if (oldest != null) {
						if (plugin.getConfigFile().isDropItemsOnGraveRemoval()) {
							oldest.dropItemsOnGround(entity);
						}
						oldest.removeGrave();
						entity.sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatGraveLimitBreak()));
					}
				}

				// Create and place the grave
				Grave grave = new Grave(plugin, new GravesConfig(entity.getUniqueId(), entity.getName(), graveLocation, itemsForGrave,
						experienceToDrop, deathMessage, System.currentTimeMillis(), false, 0, null, null));

				grave.loadChunk(false);

				if (!plugin.isUsingDisplayEntities()) {
					Block block = graveLocation.getBlock();
					block.setType(Material.PLAYER_HEAD);
					if (block.getState() instanceof Skull) {
						Skull skull = (Skull) block.getState();
						skull.setOwningPlayer(entity);
						skull.update();
					}
				}

				grave.createHologram();
				grave.checkTimeLimit(plugin.getConfigFile().getGraveTimeLimit());
				plugin.addGrave(grave);
				grave.loadBlockMeta(graveLocation.getBlock());
				if (plugin.isUsingDisplayEntities()) {
					grave.createSkull();
				}

				HashMap<String, String> placeholders = new HashMap<>();
				placeholders.put("x", "" + graveLocation.getBlockX());
				placeholders.put("y", "" + graveLocation.getBlockY());
				placeholders.put("z", "" + graveLocation.getBlockZ());

				String msg = MessageAPI.colorize(
						com.bencodez.advancedcore.api.messages.PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatDeath(), placeholders));
				if (!msg.isEmpty()) {
					entity.sendMessage(msg);
				}

				plugin.getLogger().info("Grave for " + entity.getName() + " created at: " + graveLocation);
			});
		});
	}

	/**
	 * Calculates a player's total experience based on level and progress.
	 * This is now separate to be called from an async context.
	 */
	private int getTotalExperience(int level, float expProgress) {
		int exp = 0;
		for (int i = 0; i < level; i++) {
			exp += getExpForLevel(i);
		}
		int expToLevel = getExpForLevel(level);
		exp += Math.round(expProgress * expToLevel);
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
		for (ItemStack item : inventory.getContents()) {
			if (item != null && !item.getType().isAir()) return false;
		}
		for (ItemStack item : inventory.getArmorContents()) {
			if (item != null && !item.getType().isAir()) return false;
		}
		ItemStack offHandItem = inventory.getItemInOffHand();
		return offHandItem == null || offHandItem.getType().isAir();
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		UUID playerUUID = player.getUniqueId();
		if (deathItems.containsKey(playerUUID)) {
			HashMap<Integer, ItemStack> items = deathItems.get(playerUUID);
			PlayerInventory playerInventory = player.getInventory();

			items.forEach((slot, item) -> {
				if (item == null || item.getType().isAir()) {
					return; // Continue to next item
				}

				if (slot >= 0) { // Regular inventory slot
					if (isSlotAvailable(playerInventory.getItem(slot))) {
						playerInventory.setItem(slot, item);
					} else {
						playerInventory.addItem(item);
					}
				} else { // Armor or offhand
					switch (slot) {
						case -1: // Helmet
							if (isSlotAvailable(playerInventory.getHelmet())) playerInventory.setHelmet(item);
							else playerInventory.addItem(item);
							break;
						case -2: // Chestplate
							if (isSlotAvailable(playerInventory.getChestplate())) playerInventory.setChestplate(item);
							else playerInventory.addItem(item);
							break;
						case -3: // Leggings
							if (isSlotAvailable(playerInventory.getLeggings())) playerInventory.setLeggings(item);
							else playerInventory.addItem(item);
							break;
						case -4: // Boots
							if (isSlotAvailable(playerInventory.getBoots())) playerInventory.setBoots(item);
							else playerInventory.addItem(item);
							break;
						case -5: // Offhand
							if (isSlotAvailable(playerInventory.getItemInOffHand())) playerInventory.setItemInOffHand(item);
							else playerInventory.addItem(item);
							break;
					}
				}
			});
			deathItems.remove(playerUUID); // Remove record after returning items
		}

		if (plugin.getConfigFile().isGiveCompassOnRespawn()) {
			final Player p = player;
			plugin.getBukkitScheduler().runTaskLaterAsynchronously(plugin, () -> {
				Grave grave = plugin.getLatestGrave(p);
				if (grave != null) {
					// grave.giveCompass must be thread-safe or called back on the main thread
					plugin.getBukkitScheduler().runTask(plugin, () -> grave.giveCompass(p));
				}
			}, 5L);
		}
	}

	public boolean isSlotAvailable(ItemStack slot) {
		return slot == null || slot.getType().isAir();
	}

	public boolean keepItemsWithMatchingLore(ItemStack item, String text) {
		if (item == null || item.getType().isAir() || text == null || text.isEmpty() || !item.hasItemMeta()) {
			return false;
		}
		ItemMeta meta = item.getItemMeta();
		if (meta.hasLore()) {
			for (String loreLine : meta.getLore()) {
				if (loreLine.equalsIgnoreCase(text)) {
					return true;
				}
			}
		}
		return false;
	}

	public boolean hasCurseOfVanishing(ItemStack item) {
		return item != null && item.containsEnchantment(Enchantment.VANISHING_CURSE);
	}

	public Location getAirBlock(Location loc) {
		int startY = loc.getBlockY();
		int worldMaxHeight = loc.getWorld().getMaxHeight();
		int worldMinHeight = loc.getWorld().getMinHeight();

		// Clamp startY to be within world boundaries
		if (startY >= worldMaxHeight) {
			startY = worldMaxHeight - 1;
		}
		if (startY < worldMinHeight) {
			startY = worldMinHeight;
		}

		// Search upwards first from the player's location
		for (int y = startY; y < worldMaxHeight; y++) {
			Block b = loc.getWorld().getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
			if (b.isEmpty() || isReplaceable(b.getType())) {
				return b.getLocation();
			}
		}

		// If no space found above, search downwards
		for (int y = startY - 1; y >= worldMinHeight; y--) {
			Block b = loc.getWorld().getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
			if (b.isEmpty() || isReplaceable(b.getType())) {
				return b.getLocation();
			}
		}

		return null;
	}

	public boolean isReplaceable(Material material) {
		// 恢复使用基于字符串的switch，以确保在所有环境下的兼容性。
		// .name() 在枚举中是比 .toString() 更推荐的用法。
		switch (material.name()) {
			case "TALL_GRASS":
			case "GRASS":
			case "SHORT_GRASS": // 为旧版Minecraft保留
			case "FERN":
			case "LARGE_FERN":
			case "SNOW": // 可被替换的雪层 (非雪块)
				return true;
			default:
				return false;
		}
	}
}