package com.bencodez.gravestonesplus.graves;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Skull;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;

import com.bencodez.advancedcore.api.hologram.Hologram;
import com.bencodez.advancedcore.api.inventory.BInventory;
import com.bencodez.advancedcore.api.inventory.BInventory.ClickEvent;
import com.bencodez.advancedcore.api.inventory.BInventoryButton;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.advancedcore.api.messages.PlaceholderUtils;
import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.events.GraveClaimEvent;
import com.bencodez.gravestonesplus.events.GraveRemoveEvent;
import com.bencodez.gravestonesplus.events.GraveRemoveReason;
import com.bencodez.gravestonesplus.storage.GravesConfig;
import com.bencodez.simpleapi.messages.MessageAPI;
import com.bencodez.simpleapi.time.ParsedDuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Represents a single grave instance in the world.
 */
public class Grave {

	@Getter
	private final GravesConfig gravesConfig;

	@Getter
	private Hologram topHologram;

	@Getter
	private Hologram middleHologram;

	@Getter
	private Hologram bottomHologram;

	@Getter
	private Hologram glowingHologram;

	/**
	 * Scheduled expiration task for this grave.
	 */
	private ScheduledFuture<?> expirationTask;

	@Getter
	@Setter
	private long lastClick = 0;

	private final GraveStonesPlus plugin;

	@Getter
	private boolean remove = false;

	@Getter
	private ItemDisplay itemDisplay;

	private boolean chunkLoaded = false;

	/**
	 * Constructor.
	 *
	 * @param plugin       Plugin instance
	 * @param gravesConfig Stored grave config
	 */
	public Grave(GraveStonesPlus plugin, GravesConfig gravesConfig) {
		this.plugin = plugin;
		this.gravesConfig = gravesConfig;
	}

	/**
	 * Sets metadata for quick lookup.
	 *
	 * @param block Block
	 */
	public void loadBlockMeta(Block block) {
		MiscUtils.getInstance().setBlockMeta(block, "Grave", this);
	}

	/**
	 * Calls a Bukkit event on the main thread. If called off-thread, it schedules
	 * the call for next tick.
	 *
	 * @param event Event
	 */
	private void callEventSync(final Event event) {
		if (event == null) {
			return;
		}
		if (Bukkit.isPrimaryThread()) {
			Bukkit.getPluginManager().callEvent(event);
			return;
		}
		Bukkit.getScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				Bukkit.getPluginManager().callEvent(event);
			}
		});
	}

	/**
	 * Loads the chunk for this grave and releases it later.
	 *
	 * @param task If true, run chunk load in a scheduled task
	 */
	public void loadChunk(boolean task) {
		if (task) {
			plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

				@Override
				public void run() {
					Block block = gravesConfig.getLocation().getBlock();
					if (!block.getChunk().isForceLoaded()) {
						block.getChunk().setForceLoaded(true);
						block.getChunk().load(false);
						chunkLoaded = true;
						unLoadChunk();
					}
				}
			}, gravesConfig.getLocation());
		} else {
			Block block = gravesConfig.getLocation().getBlock();
			if (!block.getChunk().isForceLoaded()) {
				block.getChunk().setForceLoaded(true);
				block.getChunk().load(false);
				chunkLoaded = true;
				unLoadChunk();
			}
		}
	}

	private void unLoadChunk() {
		plugin.getBukkitScheduler().runTaskLater(plugin, new Runnable() {

			@Override
			public void run() {
				if (chunkLoaded) {
					Block block = gravesConfig.getLocation().getBlock();
					block.getChunk().setForceLoaded(false);
					chunkLoaded = false;
				}
			}
		}, 20 * 6, gravesConfig.getLocation());
	}

	/**
	 * Gets the display type stored on this grave, falling back to global config if
	 * missing/invalid.
	 *
	 * @return Grave display type
	 */
	private GraveDisplayType getStoredDisplayTypeOrDefault() {
		try {
			String raw = gravesConfig.getGraveDisplayType();
			if (raw != null && !raw.trim().isEmpty()) {
				return GraveDisplayType.valueOf(raw.trim().toUpperCase());
			}
		} catch (Exception ignored) {
			// fall back below
		}
		return plugin.getConfigFile().getGraveDisplayTypeEnum();
	}

	/**
	 * Stores the display type on the grave config for persistence.
	 *
	 * @param type Type to store
	 */
	private void storeDisplayType(GraveDisplayType type) {
		if (type == null) {
			return;
		}
		try {
			gravesConfig.setGraveDisplayType(type.name());
		} catch (Exception ignored) {
			// best-effort
		}
	}

	/**
	 * Creates the grave marker in the world based on the stored display type.
	 */
	public void createGrave() {
		final Grave grave = this;

		final GraveDisplayType typeToUse = getStoredDisplayTypeOrDefault();
		storeDisplayType(typeToUse);

		switch (typeToUse) {
		case CHEST:
			plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

				@Override
				public void run() {
					loadChunk(false);

					Block block = grave.getGravesConfig().getLocation().getBlock();
					block.setType(Material.CHEST);

					// Fake chest only: cosmetic name, do not store items in chest inventory
					if (block.getState() instanceof Chest) {
						Chest chest = (Chest) block.getState();
						String name = "&3" + grave.getGravesConfig().getPlayerName() + "'s Grave";
						chest.setCustomName(MessageAPI.colorize(name));
						chest.update(true);
					}

					loadBlockMeta(block);
				}
			}, gravesConfig.getLocation());
			break;

		case DISPLAY_ENTITY:
			plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

				@Override
				public void run() {
					loadChunk(false);

					Block block = grave.getGravesConfig().getLocation().getBlock();

					itemDisplay = plugin.getGraveDisplayEntityHandler().createDisplay(grave);
					if (itemDisplay != null) {
						getGravesConfig().setDisplayUUID(itemDisplay.getUniqueId());
						itemDisplay.getPersistentDataContainer().set(plugin.getKey(), PersistentDataType.INTEGER, 1);
					}

					block.setType(Material.BARRIER);
					loadBlockMeta(block);
				}
			}, gravesConfig.getLocation());
			break;

		case PLAYER_HEAD:
		default:
			plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

				@Override
				public void run() {
					loadChunk(false);

					Block block = grave.getGravesConfig().getLocation().getBlock();
					block.setType(Material.PLAYER_HEAD);
					if (block.getState() instanceof Skull) {
						Skull skull = (Skull) block.getState();
						skull.setOwningPlayer(Bukkit.getOfflinePlayer(getGravesConfig().getUuid()));
						skull.update();
					}
					loadBlockMeta(block);
				}
			}, gravesConfig.getLocation());
			break;
		}
	}

	/**
	 * Removes the world marker block (legacy method name kept).
	 */
	public void removeSkull() {
		plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				Block block = gravesConfig.getLocation().getBlock();
				block.setType(Material.AIR);
			}
		}, gravesConfig.getLocation());
	}

	/**
	 * Checks ownership.
	 *
	 * @param player Player
	 * @return True if owner
	 */
	public boolean isOwner(Player player) {
		return gravesConfig.getUuid().equals(player.getUniqueId());
	}

	/**
	 * Click message handler.
	 *
	 * @param player Player
	 */
	public void onClick(Player player) {
		HashMap<String, String> placeholders = new HashMap<String, String>();
		placeholders.put("player", gravesConfig.getPlayerName());
		placeholders.put("time", "" + new Date(gravesConfig.getTime()));
		placeholders.put("reason", gravesConfig.getDeathMessage());
		player.sendMessage(MessageAPI.colorize(
				PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatClickMessage(), placeholders)));
	}

	/**
	 * Removes nearby hologram entities associated with the grave.
	 */
	public void removeHologramsAround() {
		try {
			Location hologramLocation = gravesConfig.getLocation().getBlock().getLocation().clone().add(.5, 0, .5);
			for (Entity entity : hologramLocation.getWorld().getNearbyEntities(hologramLocation, 1, 3, 1)) {
				if (entity.getType().equals(EntityType.ARMOR_STAND)
						|| entity.getType().equals(EntityType.ITEM_DISPLAY)) {
					if (entity.getPersistentDataContainer().has(plugin.getKey(), PersistentDataType.INTEGER)) {
						Integer value = entity.getPersistentDataContainer().get(plugin.getKey(),
								PersistentDataType.INTEGER);
						if (value != null && value.intValue() == 1) {
							entity.remove();
						}
					}
				}
			}
		} catch (Exception e) {
			plugin.debug(e);
		}
	}

	/**
	 * Creates the hologram lines above the grave.
	 */
	public void createHologram() {
		if (plugin.getConfigFile().isDisableArmorStands()) {
			return;
		}
		Location hologramLocation = gravesConfig.getLocation().getBlock().getLocation().clone().add(.5, 0, .5);

		HashMap<String, String> placeholders = new HashMap<String, String>();
		placeholders.put("player", gravesConfig.getPlayerName());
		placeholders.put("time", "" + new Date(gravesConfig.getTime()));
		placeholders.put("reason", gravesConfig.getDeathMessage());

		if (topHologram != null) {
			topHologram.kill();
		}
		topHologram = new Hologram(hologramLocation.add(0, 1.5, 0),
				PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatGraveTop(), placeholders), true,
				false, plugin.getKey(), 1, "Grave", this);

		if (middleHologram != null) {
			middleHologram.kill();
		}
		middleHologram = new Hologram(hologramLocation.subtract(0, .25, 0),
				PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatGraveMiddle(), placeholders), true,
				false, plugin.getKey(), 1, "Grave", this);

		if (bottomHologram != null) {
			bottomHologram.kill();
		}
		bottomHologram = new Hologram(hologramLocation.subtract(0, .25, 0),
				PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatGraveBottom(), placeholders), true,
				false, plugin.getKey(), 1, "Grave", this);

		checkGlowing();
	}

	/**
	 * Removes the hologram for this grave.
	 */
	public void removeHologram() {
		if (topHologram != null) {
			topHologram.kill();
			topHologram = null;
		}
		if (middleHologram != null) {
			middleHologram.kill();
			middleHologram = null;
		}
		if (bottomHologram != null) {
			bottomHologram.kill();
			bottomHologram = null;
		}
		if (glowingHologram != null) {
			glowingHologram.kill();
			glowingHologram = null;
		}
	}

	/**
	 * Checks if the grave marker is valid based on its stored display type.
	 *
	 * @return True if valid
	 */
	public boolean isValid() {
		GraveDisplayType type = getStoredDisplayTypeOrDefault();

		switch (type) {
		case CHEST:
			return gravesConfig.getLocation().getBlock().getType().equals(Material.CHEST);
		case DISPLAY_ENTITY:
			return gravesConfig.getLocation().getBlock().getType().equals(Material.BARRIER);
		case PLAYER_HEAD:
			return gravesConfig.getLocation().getBlock().getType().equals(Material.PLAYER_HEAD);
		default:
			return false;
		}
	}

	public String getGraveMessage() {
		Location loc = gravesConfig.getLocation();
		return "Location: " + loc.getWorld().getName() + " (" + loc.getBlockX() + "," + loc.getBlockY() + ","
				+ loc.getBlockZ() + ") Time of death: " + new Date(gravesConfig.getTime());
	}

	public boolean isOwner(String player) {
		return gravesConfig.getPlayerName().equalsIgnoreCase(player);
	}

	/**
	 * Removes the grave with a reason, firing {@link GraveRemoveEvent}.
	 *
	 * @param reason Removal reason
	 */
	public void removeGrave(final GraveRemoveReason reason) {
		// Always handle remove on main thread because timers call from async thread
		if (!Bukkit.isPrimaryThread()) {
			Bukkit.getScheduler().runTask(plugin, new Runnable() {

				@Override
				public void run() {
					removeGrave(reason);
				}
			});
			return;
		}

		GraveRemoveEvent removeEvent = new GraveRemoveEvent(this,
				reason == null ? GraveRemoveReason.MANUAL_REMOVE : reason);
		callEventSync(removeEvent);
		if (removeEvent.isCancelled()) {
			return;
		}

		remove = true;

		gravesConfig.setDestroyed(true);
		gravesConfig.setDestroyedTime(System.currentTimeMillis());

		plugin.getBukkitScheduler().runTask(GraveStonesPlus.plugin, new Runnable() {

			@Override
			public void run() {
				// Only remove the ItemDisplay if this grave is a display-entity grave
				if (getStoredDisplayTypeOrDefault() == GraveDisplayType.DISPLAY_ENTITY) {
					if (itemDisplay != null) {
						itemDisplay.remove();
						itemDisplay = null;
					}
				}

				gravesConfig.getLocation().getBlock().removeMetadata("Grave", plugin);
				gravesConfig.getLocation().getBlock().setType(Material.AIR);
			}
		}, gravesConfig.getLocation());

		removeHologram();
		removeTimer();
		plugin.removeGrave(this);
	}

	/**
	 * Removes the grave using {@link GraveRemoveReason#MANUAL_REMOVE}.
	 */
	@Deprecated
	public void removeGrave() {
		removeGrave(GraveRemoveReason.MANUAL_REMOVE);
	}

	/**
	 * Schedule the grave expiration task using the shared scheduler.
	 *
	 * @param duration the configured grave lifetime
	 */
	private void schedule(ParsedDuration duration) {
		if (duration == null) {
			return;
		}

		if (expirationTask != null && !expirationTask.isDone()) {
			expirationTask.cancel(false);
		}

		final long durationMillis = duration.getMillis();
		long deathTime = gravesConfig.getTime();
		long expireTime = deathTime + durationMillis;
		long delay = expireTime - System.currentTimeMillis();

		if (delay <= 0) {
			removeGrave(GraveRemoveReason.EXPIRED);
			return;
		}

		expirationTask = plugin.getTimer().schedule(new Runnable() {

			@Override
			public void run() {
				long currentExpireTime = gravesConfig.getTime() + durationMillis;
				if (currentExpireTime < System.currentTimeMillis()) {
					removeGrave(GraveRemoveReason.EXPIRED);
				}
			}
		}, delay + 500L, TimeUnit.MILLISECONDS);
	}

	/**
	 * Legacy support for minute-based grave time limits.
	 *
	 * @param timeLimit the time limit in minutes
	 */
	public void checkTimeLimit(int timeLimit) {
		if (timeLimit <= 0) {
			return;
		}

		checkTimeLimit(ParsedDuration.parse(timeLimit + "", TimeUnit.MINUTES));
	}

	/**
	 * Checks the configured grave time limit and removes or schedules the grave.
	 *
	 * @param timeLimitRaw the configured time limit value, supporting either legacy
	 *                     minute values such as {@code 30} or duration values such
	 *                     as {@code 30m}, {@code 2h}, or {@code 7d}
	 */
	public void checkTimeLimit(String timeLimitRaw) {
		if (timeLimitRaw == null || timeLimitRaw.trim().isEmpty()) {
			return;
		}

		String trimmed = timeLimitRaw.trim();

		try {
			if (trimmed.matches("\\d+")) {
				checkTimeLimit(Integer.parseInt(trimmed));
				return;
			}

			ParsedDuration duration = ParsedDuration.parse(trimmed, TimeUnit.MINUTES);
			checkTimeLimit(duration);
		} catch (NumberFormatException e) {
			plugin.debug("Invalid numeric grave time limit: " + timeLimitRaw);
		} catch (Exception e) {
			plugin.debug("Invalid grave time limit duration: " + timeLimitRaw);
		}
	}

	/**
	 * Checks the configured grave time limit using a parsed duration and removes or
	 * schedules the grave.
	 *
	 * @param timeLimit the parsed duration
	 */
	public void checkTimeLimit(ParsedDuration timeLimit) {
		if (timeLimit == null) {
			return;
		}

		long durationMillis = timeLimit.getMillis();
		if (durationMillis <= 0) {
			return;
		}

		long deathTime = gravesConfig.getTime();
		long expireTime = deathTime + durationMillis;

		if (expireTime < System.currentTimeMillis()) {
			removeGrave(GraveRemoveReason.EXPIRED);
		} else {
			schedule(timeLimit);
		}
	}

	public BInventoryButton getGUIItem() {
		Location loc = gravesConfig.getLocation();
		BInventoryButton b = new BInventoryButton(
				new ItemBuilder(Material.PLAYER_HEAD).setSkullOwner(Bukkit.getOfflinePlayer(gravesConfig.getUuid()))
						.setName("&3&l" + gravesConfig.getPlayerName())
						.addLoreLine("&3" + "Location: " + loc.getWorld().getName() + " (" + loc.getBlockX() + ","
								+ loc.getBlockY() + "," + loc.getBlockZ() + ")")
						.addLoreLine("&3" + "Time of death: " + new Date(gravesConfig.getTime()))
						.addLoreLine("&b" + "Click to Teleport").addLoreLine("&4Shift right click to remove")
						.addLoreLine("&cShift left click to view items")) {

			@Override
			public void onClick(ClickEvent clickEvent) {
				Grave grave = (Grave) getData("grave");
				if (clickEvent.getClick().equals(ClickType.SHIFT_RIGHT)) {
					grave.removeGrave(GraveRemoveReason.MANUAL_REMOVE);
					clickEvent.getWhoClicked().sendMessage(MessageAPI.colorize("&cGrave removed"));
				} else if (clickEvent.getClick().equals(ClickType.SHIFT_LEFT)) {
					openGUIWithItems(clickEvent.getPlayer());
				} else {
					Location loc = grave.getGravesConfig().getLocation();
					Player p = clickEvent.getWhoClicked();
					plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

						@Override
						public void run() {
							p.teleport(loc.clone().add(0, 1, 0));
						}
					}, loc);
				}
			}
		};
		b.addData("grave", this);
		return b;
	}

	public BInventoryButton getGUIItemBroken() {
		Location loc = gravesConfig.getLocation();
		BInventoryButton b = new BInventoryButton(
				new ItemBuilder(Material.PLAYER_HEAD).setSkullOwner(Bukkit.getOfflinePlayer(gravesConfig.getUuid()))
						.setName("&3&l" + gravesConfig.getPlayerName())
						.addLoreLine("&3" + "Location: " + loc.getWorld().getName() + " (" + loc.getBlockX() + ","
								+ loc.getBlockY() + "," + loc.getBlockZ() + ")")
						.addLoreLine("&3" + "Time of death: " + new Date(gravesConfig.getTime()))
						.addLoreLine("&b" + "Click to Teleport").addLoreLine("&4Shift right click to create")
						.addLoreLine("&cShift left click to view items")
						.addLoreLine("&aTime of removal: " + new Date(gravesConfig.getDestroyedTime()))) {

			@Override
			public void onClick(ClickEvent clickEvent) {
				Grave grave = (Grave) getData("grave");
				if (clickEvent.getClick().equals(ClickType.SHIFT_RIGHT)) {
					loadChunk(true);
					grave.createGrave();
					plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

						@Override
						public void run() {
							grave.createHologram();
						}
					}, grave.getGravesConfig().getLocation());
					plugin.recreateBrokenGrave(grave);
					clickEvent.getWhoClicked().sendMessage(MessageAPI.colorize("&cGrave readded"));
				} else if (clickEvent.getClick().equals(ClickType.SHIFT_LEFT)) {
					openGUIWithItems(clickEvent.getPlayer());
				} else {
					Location loc = grave.getGravesConfig().getLocation();
					Player p = clickEvent.getWhoClicked();
					plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

						@Override
						public void run() {
							p.teleport(loc.clone().add(0, 1, 0));
						}
					}, loc);
				}

			}
		};
		b.addData("grave", this);
		return b;
	}

	public String getAllGraveMessage() {
		Location loc = gravesConfig.getLocation();
		return "Player: " + gravesConfig.getPlayerName() + ", Location: " + loc.getWorld().getName() + " ("
				+ loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + ") Time of death: "
				+ new Date(gravesConfig.getTime());
	}

	public double getDistance(Player p) {
		if (p.getWorld().getUID().equals(gravesConfig.getLocation().getWorld().getUID())) {
			return gravesConfig.getLocation().distance(p.getLocation());
		}
		return -1;
	}

	public boolean isGrave(Block clicked) {
		Block currentBlock = gravesConfig.getLocation().getBlock();
		if (currentBlock.getLocation().getWorld().getUID().equals(clicked.getWorld().getUID())) {
			if (currentBlock.getX() == clicked.getX()) {
				if (currentBlock.getY() == clicked.getY()) {
					if (currentBlock.getZ() == clicked.getZ()) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * For display entities, try to find the ItemDisplay and recreate it if missing.
	 * This prevents startup from marking graves as broken just because the entity
	 * is not discoverable on the first tick.
	 */
	public void checkBlockDisplayAndFixIfMissing() {
		if (getStoredDisplayTypeOrDefault() != GraveDisplayType.DISPLAY_ENTITY) {
			return;
		}

		if (!gravesConfig.getLocation().getBlock().getType().equals(Material.BARRIER)) {
			return;
		}

		if (gravesConfig.getDisplayUUID() != null) {
			Entity e = Bukkit.getEntity(gravesConfig.getDisplayUUID());
			if (e instanceof ItemDisplay) {
				itemDisplay = (ItemDisplay) e;
				if (itemDisplay.isDead()) {
					itemDisplay = null;
				}
			}
		}

		if (itemDisplay == null) {
			try {
				itemDisplay = plugin.getGraveDisplayEntityHandler().getItemDisplay(this);
				if (itemDisplay != null && itemDisplay.isDead()) {
					itemDisplay = null;
				}
			} catch (Exception ignored) {
				// best-effort
			}
		}

		if (itemDisplay == null) {
			try {
				itemDisplay = plugin.getGraveDisplayEntityHandler().createDisplay(this);
				if (itemDisplay != null) {
					gravesConfig.setDisplayUUID(itemDisplay.getUniqueId());
					itemDisplay.getPersistentDataContainer().set(plugin.getKey(), PersistentDataType.INTEGER, 1);
				}
			} catch (Exception e) {
				plugin.debug(e);
			}
		}
	}

	public void giveCompass(Player p) {
		ItemStack compass = new ItemStack(Material.COMPASS);
		CompassMeta meta = (CompassMeta) compass.getItemMeta();
		meta.setLodestone(getGravesConfig().getLocation());
		meta.setLodestoneTracked(false);
		NamespacedKey key = new NamespacedKey(plugin, "gravecompass");
		meta.getPersistentDataContainer().set(key, PersistentDataType.LONG, getGravesConfig().getTime());
		meta.setDisplayName(p.getName() + " last grave: ");

		compass.setItemMeta(meta);

		final ItemStack itemToGive = compass;
		plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				plugin.getFullInventoryHandler().giveItem(p, itemToGive);
			}
		}, p.getLocation());
	}

	public void removeTimer() {
		if (expirationTask != null && !expirationTask.isDone()) {
			expirationTask.cancel(false);
		}
	}

	public boolean canPlayerBreak(Player player) {
		if (isOwner(player)) {
			return true;
		}
		return player.hasPermission("GraveStonesPlus.BreakOtherGraves");
	}

	public boolean canNonOwnerClaim() {
		long claimDelay = plugin.getConfigFile().getBreakOtherGravesTimeBeforeBreakable().getMillis();

		if (claimDelay <= 0) {
			return true;
		}
		long graveTime = getGravesConfig().getTime();

		return System.currentTimeMillis() >= graveTime + claimDelay;
	}

	public void checkGlowing() {
		if (!plugin.getConfigFile().isGlowingEffectNearGrave()) {
			if (glowingHologram != null) {
				glowingHologram.kill();
				glowingHologram = null;
			}
			return;
		}

		if (plugin.getConfigFile().isDisableArmorStands()) {
			if (glowingHologram != null) {
				glowingHologram.kill();
				glowingHologram = null;
			}
			return;
		}

		try {
			Location base = gravesConfig.getLocation().getBlock().getLocation().clone().add(0.5, 0.1, 0.5);
			Location glowLoc = base.clone().add(0, 0.75, 0);

			if (glowingHologram != null) {
				glowingHologram.kill();
				glowingHologram = null;
			}

			glowingHologram = new Hologram(glowLoc, MessageAPI.colorize("&e"), true, false, plugin.getKey(), 1, "Grave",
					this);
		} catch (Exception e) {
			plugin.debug(e);
		}
	}

	public void createBrokenGrave() {
		gravesConfig.setDestroyed(false);
		gravesConfig.setDestroyedTime(0);
		createGrave();
		createHologram();
	}

	public void dropItemsOnGround(Player p) {
		Location loc = getGravesConfig().getLocation();
		ArrayList<ItemStack> items = new ArrayList<ItemStack>(getGravesConfig().getItems().values());
		int chance = plugin.getConfigFile().getPercentageDrops();
		final ArrayList<ItemStack> itemsToDrop = new ArrayList<ItemStack>();
		for (ItemStack item : items) {
			if (item != null && !item.getType().equals(Material.AIR)) {
				if (chance == 100 || ThreadLocalRandom.current().nextInt(100) < chance) {
					itemsToDrop.add(item);
				}
			}
		}
		plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				for (ItemStack item : itemsToDrop) {
					if (item != null && !item.getType().equals(Material.AIR)) {
						loc.getWorld().dropItem(loc, item);
					}
				}
			}
		}, loc);
	}

	public void openGUIWithItems(Player p) {
		BInventory inv = new BInventory("Grave Items");
		for (Entry<Integer, ItemStack> entry : getGravesConfig().getItems().entrySet()) {
			switch (entry.getKey().intValue()) {
			case -1:
				inv.addButton(36, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// view only
					}
				});
				break;
			case -2:
				inv.addButton(37, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// view only
					}
				});
				break;
			case -3:
				inv.addButton(38, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// view only
					}
				});
				break;
			case -4:
				inv.addButton(39, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// view only
					}
				});
				break;
			case -5:
				inv.addButton(44, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// view only
					}
				});
				break;
			default:
				int num = entry.getKey().intValue();
				if (num < 9) {
					num = 27 + num;
				} else {
					num = num - 9;
				}

				inv.addButton(num, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// view only
					}
				});
				break;
			}
		}

		inv.addButton(53,
				new BInventoryButton(new ItemBuilder("OAK_SIGN").setName(getGravesConfig().getPlayerName())
						.addLoreLine("&cDeath: " + getGravesConfig().getDeathMessage())
						.addLoreLine("&cTime: " + getGravesConfig().getTime())
						.addLoreLine("&cEXP: " + getGravesConfig().getExp())) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// info only
					}
				});

		inv.openInventory(p);
	}

	public void claim(final Player player) {
		// Fire claim event (sync). Claim is initiated from sync events/listeners.
		GraveClaimEvent claimEvent = new GraveClaimEvent(this, player, isOwner(player));
		callEventSync(claimEvent);
		if (claimEvent.isCancelled()) {
			return;
		}

		final AdvancedCoreUser user = plugin.getUserManager().getUser(player);
		user.giveExp(getGravesConfig().getExp());

		final int chance = plugin.getConfigFile().getPercentageDrops();

		plugin.getBukkitScheduler().runTaskAsynchronously(plugin, new Runnable() {

			@Override
			public void run() {
				removeCompass();

				HashMap<Integer, ItemStack> toGive = new HashMap<Integer, ItemStack>();
				for (Entry<Integer, ItemStack> e : getGravesConfig().getItems().entrySet()) {
					if (chance == 100 || ThreadLocalRandom.current().nextInt(100) < chance) {
						ItemStack cloned = e.getValue() == null ? null : e.getValue().clone();
						toGive.put(e.getKey(), cloned);
					}
				}

				plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

					@Override
					public void run() {
						PlayerInventory inv = player.getInventory();
						ArrayList<ItemStack> leftovers = new ArrayList<ItemStack>();
						boolean notInCorrectSlot = false;

						for (Entry<Integer, ItemStack> e : toGive.entrySet()) {
							Integer key = e.getKey();
							ItemStack stack = e.getValue();
							if (stack == null || stack.getType().isAir()) {
								continue;
							}

							if (key.intValue() >= 0) {
								ItemStack cur = inv.getItem(key.intValue());
								if (cur == null || cur.getType().isAir()) {
									inv.setItem(key.intValue(), stack);
								} else {
									notInCorrectSlot = true;
									leftovers.add(stack);
								}
							} else {
								switch (key.intValue()) {
								case -1:
									if (inv.getHelmet() == null || inv.getHelmet().getType().isAir()) {
										inv.setHelmet(stack);
									} else {
										notInCorrectSlot = true;
										leftovers.add(stack);
									}
									break;
								case -2:
									if (inv.getChestplate() == null || inv.getChestplate().getType().isAir()) {
										inv.setChestplate(stack);
									} else {
										notInCorrectSlot = true;
										leftovers.add(stack);
									}
									break;
								case -3:
									if (inv.getLeggings() == null || inv.getLeggings().getType().isAir()) {
										inv.setLeggings(stack);
									} else {
										notInCorrectSlot = true;
										leftovers.add(stack);
									}
									break;
								case -4:
									if (inv.getBoots() == null || inv.getBoots().getType().isAir()) {
										inv.setBoots(stack);
									} else {
										notInCorrectSlot = true;
										leftovers.add(stack);
									}
									break;
								case -5:
									if (inv.getItemInOffHand() == null || inv.getItemInOffHand().getType().isAir()) {
										inv.setItemInOffHand(stack);
									} else {
										notInCorrectSlot = true;
										leftovers.add(stack);
									}
									break;
								default:
									notInCorrectSlot = true;
									leftovers.add(stack);
									break;
								}
							}
						}

						if (!leftovers.isEmpty()) {
							user.giveItems(leftovers.toArray(new ItemStack[0]));
						}

						user.sendMessage(plugin.getConfigFile().getFormatGraveBroke());
						if (notInCorrectSlot) {
							user.sendMessage(plugin.getConfigFile().getFormatItemsNotInGrave());
						}

						removeHologramsAround();
						removeGrave(GraveRemoveReason.CLAIMED);
					}
				});
			}
		});
	}

	public void removeCompass() {
		NamespacedKey key = new NamespacedKey(plugin, "gravecompass");
		for (Player player : Bukkit.getOnlinePlayers()) {
			for (ItemStack item : player.getInventory().getContents()) {
				if (item != null && item.getType().equals(Material.COMPASS)) {
					if (item.hasItemMeta() && item.getItemMeta() instanceof CompassMeta) {
						CompassMeta meta = (CompassMeta) item.getItemMeta();
						if (meta.getPersistentDataContainer().has(key, PersistentDataType.LONG)) {
							Long v = meta.getPersistentDataContainer().get(key, PersistentDataType.LONG);
							if (v != null
									&& (v.longValue() == getGravesConfig().getTime() || (meta.getLodestone() != null
											&& meta.getLodestone().equals(getGravesConfig().getLocation())))) {
								player.getInventory().remove(item);
							}
						}
					}
				}
			}
		}
	}

	public void checkBlockDisplay() {
		boolean hasDisplayEntities = true;
		try {
			@SuppressWarnings("unused")
			EntityType type = EntityType.ITEM_DISPLAY;
		} catch (NoSuchFieldError e) {
			hasDisplayEntities = false;
		}
		if (!hasDisplayEntities) {
			itemDisplay = null;
			return;
		}

		if (getStoredDisplayTypeOrDefault() != GraveDisplayType.DISPLAY_ENTITY) {
			itemDisplay = null;
			return;
		}

		if (!gravesConfig.getLocation().getBlock().getType().equals(Material.BARRIER)) {
			return;
		}

		if (gravesConfig.getDisplayUUID() != null) {
			Entity e = Bukkit.getEntity(gravesConfig.getDisplayUUID());
			if (e instanceof ItemDisplay && !e.isDead()) {
				itemDisplay = (ItemDisplay) e;
			}
		}

		if (itemDisplay == null) {
			itemDisplay = plugin.getGraveDisplayEntityHandler().getItemDisplay(this);
			if (itemDisplay != null && itemDisplay.isDead()) {
				itemDisplay = null;
			}
		}

		if (itemDisplay == null) {
			try {
				ItemDisplay created = plugin.getGraveDisplayEntityHandler().createDisplay(this);
				if (created != null) {
					itemDisplay = created;
					gravesConfig.setDisplayUUID(created.getUniqueId());
					created.getPersistentDataContainer().set(plugin.getKey(), PersistentDataType.INTEGER, 1);
				}
			} catch (Exception e) {
				plugin.debug(e);
			}
		}
	}
}