package com.bencodez.gravestonesplus.listeners;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GravesConfig;
import com.bencodez.gravestonesplus.nbt.NBTRule;
import com.bencodez.simpleapi.array.ArrayUtils;
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
import org.bukkit.scheduler.BukkitRunnable; // Import BukkitRunnable

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.concurrent.ConcurrentHashMap; // For thread safety

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

	// Use ConcurrentHashMap to ensure thread safety when accessed from different threads
	private final ConcurrentHashMap<UUID, HashMap<Integer, ItemStack>> deathItems = new ConcurrentHashMap<>();

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
		// If the NBTItem has no NBT data, no need to check further
		if (!nbtItem.hasNBTData()) {
			return false;
		}

		for (NBTRule rule : rules) {
			String path = rule.getPath();
			String type = rule.getType();
			Object expectedValue = rule.getValue();

			String[] pathParts = path.split("\\.");
			NBTCompound currentCompound = nbtItem;

			// Traverse the path up to the compound tag preceding the final tag
			for (int i = 0; i < pathParts.length - 1; i++) {
				String part = pathParts[i];
				// Warning for paths containing list indices (not fully supported by this direct implementation)
				if (part.matches("\\d+")) {
					plugin.getLogger().warning("NBT rule '" + rule.getName() + "' path '" + path + "' contains list index which is not fully supported by this direct implementation.");
					currentCompound = null;
					break;
				}

				if (currentCompound.hasTag(part)) {
					currentCompound = currentCompound.getCompound(part);
				} else {
					currentCompound = null; // A compound tag in the path does not exist
					break;
				}
			}

			if (currentCompound == null) {
				continue; // Path does not match, try next rule
			}

			String finalTag = pathParts[pathParts.length - 1];

			if (!currentCompound.hasTag(finalTag)) {
				continue; // Final tag does not exist, try next rule
			}

			// If the rule type is "EXISTS", it matches as long as the tag exists
			if ("EXISTS".equalsIgnoreCase(type)) {
				return true;
			}

			Object actualValue = null;
			try {
				// Read the actual NBT value based on the rule type
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
						continue; // Unsupported type, try next rule
				}
			} catch (Exception e) {
				plugin.getLogger().warning("Error reading NBT for rule '" + rule.getName() + "' (Path: " + path + ", Type: " + type + "): " + e.getMessage());
				continue; // Failed to read NBT, try next rule
			}

			// Compare actual and expected values
			if (actualValue instanceof Number && expectedValue instanceof Number) {
				// For number types, use doubleValue for floating-point comparison to avoid precision issues
				if (((Number) actualValue).doubleValue() == ((Number) expectedValue).doubleValue()) {
					return true;
				}
			} else if (actualValue != null && actualValue.equals(expectedValue)) {
				// For non-number types, use the equals method for comparison
				return true;
			}
		}
		return false; // No rule matched
	}


	@EventHandler(priority = EventPriority.HIGHEST)
	public void onPlayerDeath(PlayerDeathEvent event) {
		final Player entity = event.getEntity();
		final Location deathLocation = entity.getLocation();

		// Preliminary checks before asynchronous operations. These checks do not require extensive interaction with the Bukkit API and can be performed synchronously.
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

		if (plugin.getPvpManager() != null) {
			if (!plugin.getPvpManager().canHaveGrave(entity)) {
				plugin.getLogger().info("Can't create grave, player was in combat");
				return;
			}
		}

		if (isInventoryEmpty(entity.getInventory())) {
			if (!plugin.getConfigFile().isCreateGraveForEmptyInventories()) {
				plugin.getLogger().info("Not creating grave, player has an empty inventory");
				return;
			}
		}

		// Capture death message and experience, these must be completed before the event handler finishes.
		final String deathMessage = event.getDeathMessage();
		final int droppedExp;
		if (plugin.getConfigFile().isKeepAllExp()) {
			droppedExp = getTotalExperience(entity);
		} else {
			droppedExp = event.getDroppedExp();
		}

		// Clear Bukkit's default drops, this must be executed on the main thread.
		event.setDroppedExp(0);
		event.getDrops().clear();

		// Move time-consuming logic (like finding empty blocks and processing items) to an asynchronous task.
		new BukkitRunnable() {
			@Override
			public void run() {
				// Execute these operations in an asynchronous thread.
				Location emptyBlock = null;
				// Find an empty block, this may require iterating through blocks and can be done asynchronously.
				if (deathLocation.getBlock().isEmpty() && deathLocation.getBlockY() > deathLocation.getWorld().getMinHeight()
						&& deathLocation.getBlockY() < deathLocation.getWorld().getMaxHeight()) {
					emptyBlock = deathLocation;
				} else {
					emptyBlock = getAirBlock(deathLocation);
				}

				if (emptyBlock == null) {
					plugin.getLogger().info("Failed to find air block, can't make grave");
					return; // Terminate the task if no empty block is found.
				}

				// Create a copy of the player's inventory for safe processing in the asynchronous thread.
				PlayerInventory inv = entity.getInventory();
				// Since inventory contents might change on the main thread, we take a snapshot here.
				// Note: ItemStack is not thread-safe, so if it's to be modified in an async thread, a deep copy is needed.
				// But here we are just reading and categorizing, so direct use is fine.
				HashMap<Integer, ItemStack> currentInventoryContents = new HashMap<>();
				for (int i = 0; i < 36; i++) { // Main inventory (0-35)
					ItemStack item = inv.getItem(i);
					if (item != null) currentInventoryContents.put(i, item.clone()); // Clone to ensure thread safety
				}
				if (inv.getHelmet() != null) currentInventoryContents.put(-1, inv.getHelmet().clone());
				if (inv.getChestplate() != null) currentInventoryContents.put(-2, inv.getChestplate().clone());
				if (inv.getLeggings() != null) currentInventoryContents.put(-3, inv.getLeggings().clone());
				if (inv.getBoots() != null) currentInventoryContents.put(-4, inv.getBoots().clone());
				if (inv.getItemInOffHand() != null) currentInventoryContents.put(-5, inv.getItemInOffHand().clone());


				// Store items that will go into the grave and items that will be kept.
				HashMap<Integer, ItemStack> itemsWithSlot = new HashMap<>(); // Items to be placed in the grave
				HashMap<Integer, ItemStack> keepItems = new HashMap<>();     // Items to be kept in the player's inventory

				final HashMap<String, String> playerPlaceholders = new HashMap<>();
				playerPlaceholders.put("player", entity.getName());
				playerPlaceholders.put("displayname", entity.getDisplayName());

				BiConsumer<Integer, ItemStack> processItem = (idx, item) -> {
					if (item != null && !item.getType().isAir()) {
						// --- Priority 1: Ignore NBT Rules ---
						if (plugin.nbtConfigManager != null && doesItemMatchAnyRule(item, plugin.nbtConfigManager.getIgnoreNbtRules())) {
							return;
						}

						// --- Priority 2: Slimefun Soulbound Items ---
						if (plugin.getSlimefun() != null && plugin.getSlimefun().isSoulBoundItem(item)) {
							return;
						}

						// --- Priority 3: Lore Matching Rules ---
						final String loreToMatch = com.bencodez.advancedcore.api.messages.PlaceholderUtils.replacePlaceHolder(
								plugin.getConfigFile().getKeepItemsWithLore(),
								playerPlaceholders);
						if (keepItemsWithMatchingLore(item, loreToMatch)) {
							keepItems.put(idx, item);
							return;
						}

						// --- Priority 4: Keep NBT Rules ---
						if (plugin.nbtConfigManager != null && doesItemMatchAnyRule(item, plugin.nbtConfigManager.getKeepNbtRules())) {
							keepItems.put(idx, item);
							return;
						}

						// --- Priority 5: Grave NBT Rules ---
						if (plugin.nbtConfigManager != null && doesItemMatchAnyRule(item, plugin.nbtConfigManager.getGraveNbtRules())) {
							itemsWithSlot.put(idx, item);
							return;
						}

						// --- Priority 6: Curse of Vanishing ---
						if (hasCurseOfVanishing(item)) {
							return;
						}

						// --- Priority 7: Default behavior (if none of the above rules match) ---
						itemsWithSlot.put(idx, item);
					}
				};

				// Iterate through inventory and process items.
				for (Integer i : currentInventoryContents.keySet()) {
					processItem.accept(i, currentInventoryContents.get(i));
				}


				// Before putting items into deathItems, check the grave limit.
				// Note: plugin.numberOfGraves and plugin.getOldestGrave might involve accessing plugin internal data.
				// If these operations involve file I/O or databases, they should also be asynchronous.
				// However, if they only access in-memory HashMaps, executing them here is safe.
				if (plugin.numberOfGraves(entity.getUniqueId()) >= plugin.getConfigFile().getGraveLimit()) {
					Grave oldest = plugin.getOldestGrave(entity.getUniqueId());
					if (oldest != null) {
						// DropItemsOnGraveRemoval and removeGrave might require the main thread, but here it's just a check.
						// The actual deletion and dropping operations will be handled by the main thread task below.
						// For now, it's not processed here; the main thread task will handle it.
						plugin.getLogger().info("Grave limit reached for " + entity.getName() + ", oldest grave might be removed.");
						//entity.sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatGraveLimitBreak()));
					}
				}

				if (!keepItems.isEmpty()) {
					deathItems.put(entity.getUniqueId(), keepItems);
				}

				final Location finalEmptyBlock = emptyBlock; // Ensure it's final or effectively final for inner class.
				final HashMap<Integer, ItemStack> finalItemsWithSlot = itemsWithSlot;


				// Grave creation and Bukkit API calls must return to the main thread.
				new BukkitRunnable() {
					@Override
					public void run() {
						// Re-check grave limit and execute removal operations on the main thread.
						if (plugin.numberOfGraves(entity.getUniqueId()) >= plugin.getConfigFile().getGraveLimit()) {
							Grave oldest = plugin.getOldestGrave(entity.getUniqueId());
							if (oldest != null) {
								if (plugin.getConfigFile().isDropItemsOnGraveRemoval()) {
									oldest.dropItemsOnGround(entity); // Bukkit API: Must be on main thread.
								}
								oldest.removeGrave(); // Bukkit API: Must be on main thread.
								entity.sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatGraveLimitBreak())); // Bukkit API: Must be on main thread.
							}
						}

						Grave grave = new Grave(plugin,
								new GravesConfig(entity.getUniqueId(), entity.getName(), finalEmptyBlock, finalItemsWithSlot,
										droppedExp, deathMessage, System.currentTimeMillis(), false, 0, null, null));
						grave.loadChunk(false); // Bukkit API: Must be on main thread.

						if (!plugin.isUsingDisplayEntities()) {
							Block block = finalEmptyBlock.getBlock();
							block.setType(Material.PLAYER_HEAD); // Bukkit API: Must be on main thread.
							if (block.getState() instanceof Skull) {
								Skull skull = (Skull) block.getState();
								skull.setOwningPlayer(event.getEntity()); // Bukkit API: Must be on main thread.
								skull.update(); // Bukkit API: Must be on main thread.
							}
						}

						grave.createHologram(); // Bukkit API: Must be on main thread.
						grave.checkTimeLimit(plugin.getConfigFile().getGraveTimeLimit());
						plugin.addGrave(grave);
						grave.loadBlockMeta(finalEmptyBlock.getBlock()); // Bukkit API: Must be on main thread.
						if (plugin.isUsingDisplayEntities()) {
							grave.createSkull(); // Bukkit API: Must be on main thread.
						}

						HashMap<String, String> placeholders = new HashMap<>();
						placeholders.put("x", "" + finalEmptyBlock.getBlockX());
						placeholders.put("y", "" + finalEmptyBlock.getBlockY());
						placeholders.put("z", "" + finalEmptyBlock.getBlockZ());

						String msg = MessageAPI.colorize(
								com.bencodez.advancedcore.api.messages.PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatDeath(), placeholders));
						if (!msg.isEmpty()) {
							entity.sendMessage(msg); // Bukkit API: Must be on main thread.
						}

						plugin.getLogger().info("Grave: " + finalEmptyBlock.toString());
					}
				}.runTaskLater(plugin, 2); // Execute on main thread after 2 ticks.
			}
		}.runTaskAsynchronously(plugin); // Execute immediately on an asynchronous thread.
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
		ItemStack offHandItem = inventory.getItemInOffHand();
		if (offHandItem != null && !offHandItem.getType().isAir()) {
			return false;
		}
		return true;
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onRespawn(PlayerRespawnEvent event) {
		Player player = event.getPlayer();
		UUID playerUUID = player.getUniqueId();
		if (deathItems.containsKey(playerUUID)) {
			HashMap<Integer, ItemStack> items = deathItems.get(playerUUID);
			PlayerInventory playerInventory = player.getInventory();

			for (Integer i : items.keySet()) {
				ItemStack itemToRestore = items.get(i);
				if (itemToRestore == null || itemToRestore.getType().isAir()) {
					continue;
				}

				// All operations on playerInventory must be on the main thread.
				if (i >= 0) { // Regular inventory slot
					if (playerInventory.getItem(i) == null
							|| playerInventory.getItem(i).getType().equals(Material.AIR)) {
						playerInventory.setItem(i, itemToRestore);
					} else {
						// If slot is occupied, try to add to the first available inventory spot
						playerInventory.addItem(itemToRestore);
					}
				} else { // Armor slot or offhand
					switch (i) {
						case -1: // Helmet
							if (isSlotAvailable(playerInventory.getHelmet())) {
								playerInventory.setHelmet(itemToRestore);
							} else {
								playerInventory.addItem(itemToRestore);
							}
							break;
						case -2: // Chestplate
							if (isSlotAvailable(playerInventory.getChestplate())) {
								playerInventory.setChestplate(itemToRestore);
							} else {
								playerInventory.addItem(itemToRestore);
							}
							break;
						case -3: // Leggings
							if (isSlotAvailable(playerInventory.getLeggings())) {
								playerInventory.setLeggings(itemToRestore);
							} else {
								playerInventory.addItem(itemToRestore);
							}
							break;
						case -4: // Boots
							if (isSlotAvailable(playerInventory.getBoots())) {
								playerInventory.setBoots(itemToRestore);
							} else {
								playerInventory.addItem(itemToRestore);
							}
							break;
						case -5: // Offhand
							if (isSlotAvailable(playerInventory.getItemInOffHand())) {
								playerInventory.setItemInOffHand(itemToRestore);
							} else {
								playerInventory.addItem(itemToRestore);
							}
							break;
					}
				}
			}
			deathItems.remove(playerUUID); // Remove record after returning items
		}

		if (plugin.getConfigFile().isGiveCompassOnRespawn()) {
			final Player p = player;
			// Correction: The giveCompass operation involves giving items, which must be executed on the main thread.
			// Therefore, changed runTaskLaterAsynchronously to runTaskLater.
			plugin.getBukkitScheduler().runTaskLater(plugin, new Runnable() {
				@Override
				public void run() {
					Grave grave = plugin.getLatestGrave(p);
					if (grave != null) {
						grave.giveCompass(p); // Bukkit API: Must be on main thread.
					}
				}
			}, 5);
		}
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
				// Iterate through each line of the item's Lore and compare with the provided text
				for (String loreLine : meta.getLore()) {
					if (loreLine.equalsIgnoreCase(text)) {
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
				return true;
			default:
				return false;
		}
	}
}
