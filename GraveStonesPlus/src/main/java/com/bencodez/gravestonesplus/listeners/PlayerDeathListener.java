package com.bencodez.gravestonesplus.listeners;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GravesConfig;
import com.bencodez.gravestonesplus.nbt.NBTRule;
import com.bencodez.gravestonesplus.nbt.NBTConfigManager;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

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

			// Debug log (optional, enable only for troubleshooting)
			// plugin.getLogger().info("  Evaluating NBT rule: " + rule.getName() + " for item: " + item.getType().name() + " (Path: " + path + ", Type: " + type + ", Expected Value: " + expectedValue + ")");

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

		if (isInventoryEmpty(event.getEntity().getInventory())) {
			if (!plugin.getConfigFile().isCreateGraveForEmptyInventories()) {
				plugin.getLogger().info("Not creating grave, player has an empty inventory");
				return;
			}
		}

		final Player entity = event.getEntity();
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

		Location emptyBlock = null;
		if (deathLocation.getBlock().isEmpty() && deathLocation.getBlockY() > deathLocation.getWorld().getMinHeight()
				&& deathLocation.getBlockY() < deathLocation.getWorld().getMaxHeight()) {
			emptyBlock = deathLocation;
		} else {
			emptyBlock = getAirBlock(deathLocation);
		}

		if (emptyBlock == null) {
			plugin.getLogger().info("Failed to find air block, can't make grave");
			return;
		}

		PlayerInventory inv = event.getEntity().getInventory();

		// Important improvement: Define the placeholder map here and use it inside processItem
		// This ensures that placeholders are correctly replaced for Lore rules each time an item is processed
		final HashMap<String, String> playerPlaceholders = new HashMap<>();
		playerPlaceholders.put("player", entity.getName());
		playerPlaceholders.put("displayname", entity.getDisplayName());
		// Add any other player-related placeholders you might need here

		final String deathMessage = event.getDeathMessage();
		if (plugin.getConfigFile().isKeepAllExp()) {
			event.setDroppedExp(getTotalExperience(event.getEntity()));
		}
		final int droppedExp = event.getDroppedExp();
		event.setDroppedExp(0); // Clear Bukkit's default dropped experience
		event.getDrops().clear(); // Clear Bukkit's default dropped items

		// Store items that will go into the grave and items that will be kept
		HashMap<Integer, ItemStack> itemsWithSlot = new HashMap<Integer, ItemStack>(); // Items to be placed in the grave
		HashMap<Integer, ItemStack> keepItems = new HashMap<Integer, ItemStack>();     // Items to be kept in the player's inventory

		BiConsumer<Integer,ItemStack> processItem = (idx,item) -> {
			if (item != null && !item.getType().isAir()) { // Ensure item exists and is not air
				//plugin.getLogger().info("Processing item: " + item.getType().name() + " at slot " + idx); // Debug log

				// --- Priority 1: Ignore NBT Rules ---
				if (plugin.nbtConfigManager != null && doesItemMatchAnyRule(item, plugin.nbtConfigManager.getIgnoreNbtRules())) {
					//plugin.getLogger().info("  Item " + item.getType().name() + " matched an 'ignore' NBT rule. Skipping further processing.");
					return; // Return immediately, this item is not handled by GraveStonesPlus
				}

				// --- Priority 2: Slimefun Soulbound Items ---
				if (plugin.getSlimefun() != null && plugin.getSlimefun().isSoulBoundItem(item)) {
					//plugin.getLogger().info("  Item " + item.getType().name() + " is Slimefun Soulbound. Allowing Slimefun to handle.");
					return; // Assume Slimefun will handle and keep these items itself, so we don't interfere
				}

				// --- Priority 3: Lore Matching Rules ---
				// Here, playerPlaceholders will be used to replace placeholders in the keepItemsWithLore config
				final String loreToMatch = com.bencodez.advancedcore.api.messages.PlaceholderUtils.replacePlaceHolder(
						plugin.getConfigFile().getKeepItemsWithLore(),
						playerPlaceholders);
				if (keepItemsWithMatchingLore(item, loreToMatch)) {
					keepItems.put(idx, item);
					//plugin.getLogger().info("  Item " + item.getType().name() + " kept due to matching lore: '" + loreToMatch + "'");
					return;
				}

				// --- Priority 4: Keep NBT Rules ---
				if (plugin.nbtConfigManager != null && doesItemMatchAnyRule(item, plugin.nbtConfigManager.getKeepNbtRules())) {
					keepItems.put(idx, item);
					//plugin.getLogger().info("  Item " + item.getType().name() + " kept due to matching NBT 'keep' rule.");
					return;
				}

				// --- Priority 5: Grave NBT Rules ---
				if (plugin.nbtConfigManager != null && doesItemMatchAnyRule(item, plugin.nbtConfigManager.getGraveNbtRules())) {
					itemsWithSlot.put(idx, item);
					//plugin.getLogger().info("  Item " + item.getType().name() + " put in grave due to matching NBT 'grave' rule.");
					return;
				}

				// --- Priority 6: Curse of Vanishing ---
				if (hasCurseOfVanishing(item)) {
					//plugin.getLogger().info("  Item " + item.getType().name() + " has Curse of Vanishing and not kept by any rule, will be removed.");
					return; // The item will not be added to any list, thus being removed
				}

				// --- Priority 7: Default behavior (if none of the above rules match) ---
				itemsWithSlot.put(idx, item);
				//plugin.getLogger().info("  Item " + item.getType().name() + " (default) put in grave.");

			} else {
				// plugin.getLogger().info("  Slot " + idx + " is empty or air, skipping.");
			}
		};

		// Iterate through player inventory slots and process items
		for (int i = 0; i < 36; i++) { // Main inventory (0-35)
			ItemStack item = inv.getItem(i);
			processItem.accept(i,item);
		}
		processItem.accept(-1,inv.getHelmet());      // Helmet
		processItem.accept(-2,inv.getChestplate());  // Chestplate
		processItem.accept(-3, inv.getLeggings());   // Leggings
		processItem.accept(-4, inv.getBoots());      // Boots
		processItem.accept(-5, inv.getItemInOffHand()); // Offhand


		if (!keepItems.isEmpty()) {
			deathItems.put(event.getEntity().getUniqueId(), keepItems);
		}

		final Location emptyBlockFinal = emptyBlock;

		plugin.getBukkitScheduler().runTaskLater(plugin, new Runnable() {
			@Override
			public void run() {
				Grave grave = new Grave(plugin,
						new GravesConfig(entity.getUniqueId(), entity.getName(), emptyBlockFinal, itemsWithSlot,
								droppedExp, deathMessage, System.currentTimeMillis(), false, 0, null, null));
				grave.loadChunk(false);

				if (!plugin.isUsingDisplayEntities()) {
					Block block = emptyBlockFinal.getBlock();
					block.setType(Material.PLAYER_HEAD);
					if (block.getState() instanceof Skull) {
						Skull skull = (Skull) block.getState();
						skull.setOwningPlayer(event.getEntity());
						skull.update();
					}
				}

				grave.createHologram();
				grave.checkTimeLimit(plugin.getConfigFile().getGraveTimeLimit());
				plugin.addGrave(grave);
				grave.loadBlockMeta(emptyBlockFinal.getBlock());
				if (plugin.isUsingDisplayEntities()) {
					grave.createSkull();
				}

				HashMap<String, String> placeholders = new HashMap<String, String>();
				placeholders.put("x", "" + emptyBlockFinal.getBlockX());
				placeholders.put("y", "" + emptyBlockFinal.getBlockY());
				placeholders.put("z", "" + emptyBlockFinal.getBlockZ());

				String msg = MessageAPI.colorize(
						com.bencodez.advancedcore.api.messages.PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatDeath(), placeholders));
				if (!msg.isEmpty()) {
					entity.sendMessage(msg);
				}

				plugin.getLogger().info("Grave: " + emptyBlockFinal.toString());
			}
		}, 2, emptyBlockFinal);

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
					continue; // Ensure item is not null or air
				}

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
			plugin.getBukkitScheduler().runTaskLaterAsynchronously(plugin, new Runnable() {
				@Override
				public void run() {
					Grave grave = plugin.getLatestGrave(p);
					if (grave != null) {
						grave.giveCompass(p);
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
				return true;
			default:
				return false;
		}
	}
}