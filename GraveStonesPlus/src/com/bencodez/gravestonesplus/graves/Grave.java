package com.bencodez.gravestonesplus.graves;

import java.util.Date;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Skull;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.persistence.PersistentDataType;

import com.bencodez.advancedcore.api.hologram.Hologram;
import com.bencodez.advancedcore.api.inventory.BInventory.ClickEvent;
import com.bencodez.advancedcore.api.inventory.BInventoryButton;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.advancedcore.api.messages.StringParser;
import com.bencodez.advancedcore.api.misc.StringUtils;
import com.bencodez.gravestonesplus.GraveStonesPlus;

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

	public void createSkull() {
		Bukkit.getScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				Block block = gravesConfig.getLocation().getBlock();
				block.setType(Material.PLAYER_HEAD);
				if (block.getState() instanceof Skull) {
					Skull skull = (Skull) block.getState();
					skull.setOwningPlayer(Bukkit.getOfflinePlayer(getGravesConfig().getUuid()));
					skull.update();
				}
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
		player.sendMessage(gravesConfig.getPlayerName() + "'s grave. Died at " + gravesConfig.getTime() + ". Reason: "
				+ gravesConfig.getDeathMessage());
	}

	public void removeHologramsAround() {
		// try/catch to prevent unexpected issues
		try {
			Location hologramLocation = gravesConfig.getLocation().getBlock().getLocation().clone().add(.5, 0, .5);
			for (Entity entity : hologramLocation.getWorld().getNearbyEntities(hologramLocation, 2, 3, 2)) {
				if (entity.getType().equals(EntityType.ARMOR_STAND)) {
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
		Location hologramLocation = gravesConfig.getLocation().getBlock().getLocation().clone().add(.5, 0, .5);

		HashMap<String, String> placeholders = new HashMap<String, String>();
		placeholders.put("player", gravesConfig.getPlayerName());
		placeholders.put("time", "" + new Date(gravesConfig.getTime()));
		placeholders.put("reason", gravesConfig.getDeathMessage());
		topHologram = new Hologram(hologramLocation.add(0, 1.5, 0),
				StringParser.getInstance().replacePlaceHolder(plugin.getConfigFile().getFormatGraveTop(), placeholders),
				true, false, plugin.getKey(), 1);
		// topHologram.getPersistentDataHolder().set(plugin.getKey(),
		// PersistentDataType.INTEGER, 1);
		middleHologram = new Hologram(hologramLocation.subtract(0, .25, 0), StringParser.getInstance()
				.replacePlaceHolder(plugin.getConfigFile().getFormatGraveMiddle(), placeholders), true, false,
				plugin.getKey(), 1);
		// middleHologram.getPersistentDataHolder().set(plugin.getKey(),
		// PersistentDataType.INTEGER, 1);
		bottomHologram = new Hologram(hologramLocation.subtract(0, .25, 0), StringParser.getInstance()
				.replacePlaceHolder(plugin.getConfigFile().getFormatGraveBottom(), placeholders), true, false,
				plugin.getKey(), 1);
		// bottomHologram.getPersistentDataHolder().set(plugin.getKey(),
		// PersistentDataType.INTEGER, 1);
		checkGlowing();
	}

	public void removeHologram() {
		topHologram.kill();
		middleHologram.kill();
		bottomHologram.kill();
		if (glowingHologram != null) {
			glowingHologram.kill();
		}
	}

	public boolean isValid() {
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
				if (p.getLocation().distance(gravesConfig.getLocation()) < GraveStonesPlus.plugin.getConfigFile()
						.getGlowingEffectDistance()) {
					if (glowingHologram == null) {
						glowingHologram = new Hologram(
								gravesConfig.getLocation().getBlock().getLocation().clone().add(.5, -2, .5), "", false,
								true, plugin.getKey(), 1);

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
				.addLoreLine("&b" + "Click to Teleport").addLoreLine("&4Shift right click to remove")) {

			@Override
			public void onClick(ClickEvent clickEvent) {
				Grave grave = (Grave) getData("grave");
				if (clickEvent.getClick().equals(ClickType.SHIFT_RIGHT)) {
					grave.removeGrave();
					clickEvent.getWhoClicked().sendMessage(StringUtils.getInstance().colorize("&cGrave removed"));
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
				.addLoreLine("Time of removal: " + new Date(gravesConfig.getDestroyedTime()))) {

			@Override
			public void onClick(ClickEvent clickEvent) {
				Grave grave = (Grave) getData("grave");
				if (clickEvent.getClick().equals(ClickType.SHIFT_RIGHT)) {
					grave.createSkull();
					Bukkit.getScheduler().runTask(plugin, new Runnable() {

						@Override
						public void run() {
							grave.createHologram();
						}
					});
					plugin.recreateBrokenGrave(grave);
					clickEvent.getWhoClicked().sendMessage(StringUtils.getInstance().colorize("&cGrave readded"));
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

}
