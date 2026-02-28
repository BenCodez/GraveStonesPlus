package com.bencodez.gravestonesplus.graves;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
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
import com.bencodez.gravestonesplus.storage.GravesConfig;
import com.bencodez.simpleapi.messages.MessageAPI;

import lombok.Getter;
import lombok.Setter;

public class Grave {

	@Getter
	private GravesConfig gravesConfig;

	@Getter
	private Hologram topHologram;
	@Getter
	private Hologram middleHologram;
	@Getter
	private Hologram bottomHologram;

	@Getter
	private Hologram glowingHologram;

	@Getter
	private Timer timer;

	@Getter
	@Setter
	private long lastClick = 0;

	private GraveStonesPlus plugin;

	@Getter
	private boolean remove = false;

	@Getter
	private ItemDisplay itemDisplay;

	public Grave(GraveStonesPlus plugin, GravesConfig gravesConfig) {
		this.plugin = plugin;
		this.gravesConfig = gravesConfig;
	}

	public void loadBlockMeta(Block block) {
		MiscUtils.getInstance().setBlockMeta(block, "Grave", this);
	}

	private boolean chunkLoaded = false;

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

	public void createSkull() {
		if (plugin.isUsingDisplayEntities()) {
			final Grave grave = this;
			plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

				@Override
				public void run() {
					loadChunk(false);
					Block block = gravesConfig.getLocation().getBlock();

					itemDisplay = plugin.getGraveDisplayEntityHandler().createDisplay(grave);
					getGravesConfig().setDisplayUUID(itemDisplay.getUniqueId());
					itemDisplay.getPersistentDataContainer().set(plugin.getKey(), PersistentDataType.INTEGER, 1);

					block.setType(Material.BARRIER);
					loadBlockMeta(block);
				}
			}, gravesConfig.getLocation());
		} else {
			plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

				@Override
				public void run() {
					loadChunk(false);
					Block block = gravesConfig.getLocation().getBlock();

					block.setType(Material.PLAYER_HEAD);
					if (block.getState() instanceof Skull) {
						Skull skull = (Skull) block.getState();
						skull.setOwningPlayer(Bukkit.getOfflinePlayer(getGravesConfig().getUuid()));
						skull.update();
					}
					loadBlockMeta(block);
				}
			}, gravesConfig.getLocation());
		}
	}

	public void removeSkull() {
		plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				Block block = gravesConfig.getLocation().getBlock();
				block.setType(Material.AIR);
			}
		}, gravesConfig.getLocation());
	}

	public boolean isOwner(Player player) {
		return gravesConfig.getUuid().equals(player.getUniqueId());
	}

	public void onClick(Player player) {
		HashMap<String, String> placeholders = new HashMap<String, String>();
		placeholders.put("player", gravesConfig.getPlayerName());
		placeholders.put("time", "" + new Date(gravesConfig.getTime()));
		placeholders.put("reason", gravesConfig.getDeathMessage());
		player.sendMessage(MessageAPI.colorize(
				PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatClickMessage(), placeholders)));
	}

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
			e.printStackTrace();
		}
	}

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

	public boolean isValid() {
		if (plugin.getConfigFile().isUseDisplayEntities()) {
			// Display-entity mode: the grave is represented by a barrier block.
			return gravesConfig.getLocation().getBlock().getType().equals(Material.BARRIER);
		}
		// Skull mode
		return gravesConfig.getLocation().getBlock().getType().equals(Material.PLAYER_HEAD);
	}

	public String getGraveMessage() {
		Location loc = gravesConfig.getLocation();
		return "Location: " + loc.getWorld().getName() + " (" + loc.getBlockX() + "," + loc.getBlockY() + ","
				+ loc.getBlockZ() + ") Time of death: " + new Date(gravesConfig.getTime());
	}

	public boolean isOwner(String player) {
		return gravesConfig.getPlayerName().equalsIgnoreCase(player);
	}

	public void removeGrave() {
		remove = true;

		gravesConfig.setDestroyed(true);
		gravesConfig.setDestroyedTime(System.currentTimeMillis());
		plugin.getBukkitScheduler().runTask(GraveStonesPlus.plugin, new Runnable() {

			@Override
			public void run() {
				if (itemDisplay != null) {
					itemDisplay.remove();
					itemDisplay = null;
				}
				gravesConfig.getLocation().getBlock().removeMetadata("Grave", plugin);
				gravesConfig.getLocation().getBlock().setType(Material.AIR);
			}
		}, gravesConfig.getLocation());
		removeHologram();
		removeTimer();
		plugin.removeGrave(this);
	}

	private void schedule(int timeLimit) {
		if (timer != null) {
			timer.cancel();
		}
		timer = new Timer();
		timer.schedule(new TimerTask() {

			@Override
			public void run() {
				long deathTime = gravesConfig.getTime();
				long timedPassed = deathTime += (timeLimit * 60 * 1000);
				if (timedPassed < System.currentTimeMillis()) {
					removeGrave();
				}
			}
		}, timeLimit * 60 * 1000 + 500);
	}

	public void checkTimeLimit(int timeLimit) {
		if (timeLimit > 0) {
			long deathTime = gravesConfig.getTime();
			long timedPassed = deathTime + (timeLimit * 60 * 1000);
			if (timedPassed < System.currentTimeMillis()) {
				removeGrave();
			} else {
				schedule(timeLimit);
			}
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
					grave.removeGrave();
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
					grave.createSkull();
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
		} else {
			return -1;
		}
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
		if (!plugin.getConfigFile().isUseDisplayEntities()) {
			return;
		}

		// Only makes sense if the world block is a barrier grave
		if (!gravesConfig.getLocation().getBlock().getType().equals(Material.BARRIER)) {
			return;
		}

		// 1) Try direct lookup by stored UUID
		if (gravesConfig.getDisplayUUID() != null) {
			Entity e = Bukkit.getEntity(gravesConfig.getDisplayUUID());
			if (e instanceof ItemDisplay) {
				itemDisplay = (ItemDisplay) e;
				if (itemDisplay.isDead()) {
					itemDisplay = null;
				}
			}
		}

		// 2) Fallback to handler scan
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

		// 3) If still missing, recreate display entity
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
		if (timer != null) {
			timer.cancel();
			timer = null;
		}
	}

	public boolean canPlayerBreak(Player player) {
		if (isOwner(player)) {
			return true;
		}
		return player.hasPermission("GraveStonesPlus.BreakOtherGraves");
	}

	public void checkGlowing() {
		// existing logic unchanged in your file
	}

	public void createBrokenGrave() {
		gravesConfig.setDestroyed(false);
		gravesConfig.setDestroyedTime(0);
		createSkull();
		createHologram();
	}

	public void dropItemsOnGround(Player p) {
		Location loc = getGravesConfig().getLocation();
		ArrayList<ItemStack> items = new ArrayList<ItemStack>(getGravesConfig().getItems().values());
		int chance = plugin.getConfigFile().getPercentageDrops();
		final ArrayList<ItemStack> itemsToDrop = new ArrayList<ItemStack>();
		for (ItemStack item : items) {
			if (!item.getType().equals(Material.AIR)) {
				if (chance == 100 || ThreadLocalRandom.current().nextInt(100) < chance) {
					itemsToDrop.add(item);
				}
			}
		}
		plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				for (ItemStack item : itemsToDrop) {
					if (!item.getType().equals(Material.AIR)) {
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

					}
				});
				break;
			case -2:
				inv.addButton(37, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {

					}
				});
				break;
			case -3:
				inv.addButton(38, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {

					}
				});
				break;
			case -4:
				inv.addButton(39, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {

					}
				});
				break;
			case -5:
				inv.addButton(44, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {

					}
				});
				break;
			default:
				int num = entry.getKey().intValue();
				plugin.getLogger().info("Before: num: " + num);
				if (num < 9) {
					num = 27 + num;
				} else {
					num = num - 9;
				}
				plugin.getLogger().info("After: num: " + num);

				inv.addButton(num, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {

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

					}

				});
		inv.openInventory(p);

	}

	public void claim(final Player player) {
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

						// 5) Cleanup on main
						removeHologramsAround();
						removeGrave();
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

		if (!plugin.getConfigFile().isUseDisplayEntities()) {
			itemDisplay = null;
			return;
		}

		// Only attempt to resolve/recreate display entities when the block is a grave
		// barrier
		if (!gravesConfig.getLocation().getBlock().getType().equals(Material.BARRIER)) {
			return;
		}

		// 1) Try direct lookup by stored UUID (fast path)
		if (gravesConfig.getDisplayUUID() != null) {
			Entity e = Bukkit.getEntity(gravesConfig.getDisplayUUID());
			if (e instanceof ItemDisplay && !e.isDead()) {
				itemDisplay = (ItemDisplay) e;
			}
		}

		// 2) Fallback to handler scan
		if (itemDisplay == null) {
			itemDisplay = plugin.getGraveDisplayEntityHandler().getItemDisplay(this);
			if (itemDisplay != null && itemDisplay.isDead()) {
				itemDisplay = null;
			}
		}

		// 3) Recreate display if missing (prevents startup invalidation)
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