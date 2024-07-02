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

	public Grave(GraveStonesPlus plugin, GravesConfig gravesConfig) {
		this.plugin = plugin;
		this.gravesConfig = gravesConfig;
	}

	@Getter
	private ItemDisplay itemDisplay;

	public void loadBlockMeta(Block block) {
		MiscUtils.getInstance().setBlockMeta(block, "Grave", this);
	}

	private boolean chunkLoaded = false;

	public void loadChunk(boolean task) {
		if (task) {
			Bukkit.getScheduler().runTask(plugin, new Runnable() {

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
			});
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
		Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {

			@Override
			public void run() {
				if (chunkLoaded) {
					Block block = gravesConfig.getLocation().getBlock();
					block.getChunk().setForceLoaded(false);
					chunkLoaded = false;
				}
			}
		}, 20 * 6);

	}

	public void createSkull() {
		if (plugin.isUsingDisplayEntities()) {
			final Grave grave = this;
			Bukkit.getScheduler().runTask(plugin, new Runnable() {

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
			});
		} else {
			Bukkit.getScheduler().runTask(plugin, new Runnable() {

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

			});
		}
	}

	public void removeSkull() {
		Bukkit.getScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				Block block = gravesConfig.getLocation().getBlock();
				block.setType(Material.AIR);
			}
		});
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

	public boolean isOwner(Player player) {
		if (gravesConfig.getUuid().equals(player.getUniqueId())) {
			return true;
		}
		return false;
	}

	public void onClick(Player player) {
		player.sendMessage(gravesConfig.getPlayerName() + "'s grave. Died at " + new Date(gravesConfig.getTime())
				+ ". Reason: " + gravesConfig.getDeathMessage());
	}

	public void removeHologramsAround() {
		// try/catch to prevent unexpected issues
		try {
			Location hologramLocation = gravesConfig.getLocation().getBlock().getLocation().clone().add(.5, 0, .5);
			for (Entity entity : hologramLocation.getWorld().getNearbyEntities(hologramLocation, 1, 3, 1)) {
				if (entity.getType().equals(EntityType.ARMOR_STAND)
						|| entity.getType().equals(EntityType.ITEM_DISPLAY)) {
					if (entity.getPersistentDataContainer().has(plugin.getKey(), PersistentDataType.INTEGER)) {
						int value = entity.getPersistentDataContainer().get(plugin.getKey(),
								PersistentDataType.INTEGER);
						if (value == 1) {
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
		// topHologram.getPersistentDataHolder().set(plugin.getKey(),
		// PersistentDataType.INTEGER, 1);
		if (middleHologram != null) {
			middleHologram.kill();
		}
		middleHologram = new Hologram(hologramLocation.subtract(0, .25, 0),
				PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatGraveMiddle(), placeholders), true,
				false, plugin.getKey(), 1, "Grave", this);

		// middleHologram.getPersistentDataHolder().set(plugin.getKey(),
		// PersistentDataType.INTEGER, 1);
		if (bottomHologram != null) {
			bottomHologram.kill();
		}
		bottomHologram = new Hologram(hologramLocation.subtract(0, .25, 0),
				PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatGraveBottom(), placeholders), true,
				false, plugin.getKey(), 1, "Grave", this);
		// bottomHologram.getPersistentDataHolder().set(plugin.getKey(),
		// PersistentDataType.INTEGER, 1);
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
			if (itemDisplay != null) {
				if (!itemDisplay.isDead()) {
					if (gravesConfig.getLocation().getBlock().getType().equals(Material.BARRIER)) {
						return true;
					}
				}
			}
		}
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
		Bukkit.getScheduler().runTask(GraveStonesPlus.plugin, new Runnable() {

			@Override
			public void run() {
				if (itemDisplay != null) {
					itemDisplay.remove();
					itemDisplay = null;
				}
				gravesConfig.getLocation().getBlock().setType(Material.AIR);
			}
		});
		removeHologram();
		removeTimer();
		GraveStonesPlus.plugin.removeGrave(this);
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

	public void checkGlowing() {
		if (gravesConfig.getUuid() != null) {
			Player p = Bukkit.getPlayer(gravesConfig.getUuid());
			if (p != null) {

				if (p.getLocation().getWorld().getUID().equals(gravesConfig.getLocation().getWorld().getUID())
						&& p.getLocation().distance(gravesConfig.getLocation()) < GraveStonesPlus.plugin.getConfigFile()
								.getGlowingEffectDistance()) {
					if (glowingHologram == null) {
						glowingHologram = new Hologram(
								gravesConfig.getLocation().getBlock().getLocation().clone().add(.5, -2, .5), "", false,
								true, plugin.getKey(), 1, "Grave", this);

					}
					glowingHologram.glow(true);
				} else {
					if (glowingHologram != null) {
						glowingHologram.kill();
						glowingHologram = null;
					}
				}

			}
		}
	}

	public void removeTimer() {
		if (timer != null) {
			timer.cancel();
			timer = null;
		}
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

	@SuppressWarnings("deprecation")
	public BInventoryButton getGUIItem() {
		Location loc = gravesConfig.getLocation();
		BInventoryButton b = new BInventoryButton(new ItemBuilder(Material.PLAYER_HEAD)
				.setSkullOwner(gravesConfig.getPlayerName()).setName("&3&l" + gravesConfig.getPlayerName())
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
					Bukkit.getScheduler().runTask(plugin, new Runnable() {

						@Override
						public void run() {
							p.teleport(loc.clone().add(0, 1, 0));
						}
					});
				}
			}
		};
		b.addData("grave", this);
		return b;
	}

	@SuppressWarnings("deprecation")
	public BInventoryButton getGUIItemBroken() {
		Location loc = gravesConfig.getLocation();
		BInventoryButton b = new BInventoryButton(new ItemBuilder(Material.PLAYER_HEAD)
				.setSkullOwner(gravesConfig.getPlayerName()).setName("&3&l" + gravesConfig.getPlayerName())
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
					Bukkit.getScheduler().runTask(plugin, new Runnable() {

						@Override
						public void run() {
							grave.createHologram();
						}
					});
					plugin.recreateBrokenGrave(grave);
					clickEvent.getWhoClicked().sendMessage(MessageAPI.colorize("&cGrave readded"));
				} else if (clickEvent.getClick().equals(ClickType.SHIFT_LEFT)) {
					openGUIWithItems(clickEvent.getPlayer());
				} else {

					Location loc = grave.getGravesConfig().getLocation();
					Player p = clickEvent.getWhoClicked();
					Bukkit.getScheduler().runTask(plugin, new Runnable() {

						@Override
						public void run() {
							p.teleport(loc.clone().add(0, 1, 0));
						}
					});
				}

			}
		};
		b.addData("grave", this);
		return b;
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

	public boolean isSlotAvailable(ItemStack slot) {
		if (slot == null || slot.getType().isAir()) {
			return true;
		}
		return false;
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
		Bukkit.getScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				for (ItemStack item : itemsToDrop) {
					if (!item.getType().equals(Material.AIR)) {
						loc.getWorld().dropItem(loc, item);
					}
				}
			}
		});
	}

	public void claim(Player player, PlayerInventory currentInv) {
		AdvancedCoreUser user = plugin.getUserManager().getUser(player);
		user.giveExp(getGravesConfig().getExp());

		int chance = plugin.getConfigFile().getPercentageDrops();
		boolean notInCorrectSlot = false;
		for (ItemStack item : player.getInventory().getContents()) {
			if (item != null && item.getType().equals(Material.COMPASS)) {
				if (item.hasItemMeta()) {
					CompassMeta meta = (CompassMeta) item.getItemMeta();
					if (meta.getLodestone().equals(getGravesConfig().getLocation())) {
						player.getInventory().remove(item);
					}
				}
			}
		}

		for (Entry<Integer, ItemStack> item :

		getGravesConfig().getItems().entrySet()) {
			if (chance == 100 || ThreadLocalRandom.current().nextInt(100) < chance) {

				if (item.getKey().intValue() >= 0) {
					ItemStack currentItem = currentInv.getItem(item.getKey().intValue());
					if (isSlotAvailable(currentItem)) {
						currentInv.setItem(item.getKey().intValue(), item.getValue());
					} else {
						notInCorrectSlot = true;
						user.giveItem(item.getValue());
					}
				} else {
					switch (item.getKey().intValue()) {
					case -1:
						if (isSlotAvailable(currentInv.getHelmet())) {
							currentInv.setHelmet(item.getValue());
						} else {
							user.giveItem(item.getValue());
							notInCorrectSlot = true;
						}
						break;
					case -2:
						if (isSlotAvailable(currentInv.getChestplate())) {
							currentInv.setChestplate(item.getValue());
						} else {
							user.giveItem(item.getValue());
							notInCorrectSlot = true;
						}
						break;
					case -3:
						if (isSlotAvailable(currentInv.getLeggings())) {
							currentInv.setLeggings(item.getValue());
						} else {
							user.giveItem(item.getValue());
							notInCorrectSlot = true;
						}
						break;
					case -4:
						if (isSlotAvailable(currentInv.getBoots())) {
							currentInv.setBoots(item.getValue());
						} else {
							user.giveItem(item.getValue());
							notInCorrectSlot = true;
						}
						break;
					case -5:
						if (isSlotAvailable(currentInv.getItemInOffHand())) {
							currentInv.setItemInOffHand(item.getValue());
						} else {
							user.giveItem(item.getValue());
							notInCorrectSlot = true;
						}
						break;
					}
				}
			}
		}
		user.sendMessage(plugin.getConfigFile().getFormatGraveBroke());
		if (notInCorrectSlot) {
			user.sendMessage(plugin.getConfigFile().getFormatItemsNotInGrave());
		}
		removeGrave();
		removeHologram();
		if (Bukkit.isPrimaryThread()) {
			removeHologramsAround();
		} else {
			Bukkit.getScheduler().runTask(plugin, new Runnable() {

				@Override
				public void run() {
					removeHologramsAround();
				}
			});
		}
		plugin.removeGrave(this);
	}

	public void checkBlockDisplay() {
		itemDisplay = plugin.getGraveDisplayEntityHandler().getItemDisplay(this);
	}

}
