package com.bencodez.gravestonesplus.graves;

import java.util.Date;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import com.bencodez.advancedcore.api.hologram.Hologram;
import com.bencodez.gravestonesplus.GraveStonesPlus;

import lombok.Getter;

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

	public Grave(GravesConfig gravesConfig) {
		this.gravesConfig = gravesConfig;
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

	public void createHologram() {
		Location hologramLocation = gravesConfig.getLocation().getBlock().getLocation().clone().add(.5, 0, .5);
		topHologram = new Hologram(hologramLocation.add(0, 1.5, 0), gravesConfig.getPlayerName() + " died here!");
		middleHologram = new Hologram(hologramLocation.subtract(0, .25, 0),
				"Died at " + new Date(gravesConfig.getTime()));
		bottomHologram = new Hologram(hologramLocation.subtract(0, .25, 0),
				"Reason: " + gravesConfig.getDeathMessage());
		checkGlowing();
	}

	public void removeHologram() {
		topHologram.kill();
		middleHologram.kill();
		bottomHologram.kill();
		glowingHologram.kill();
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

	public void checkGlowing() {
		if (gravesConfig.getUuid() != null) {
			Player p = Bukkit.getPlayer(gravesConfig.getUuid());
			if (p != null) {
				if (p.getLocation().distance(gravesConfig.getLocation()) < GraveStonesPlus.plugin.getConfigFile()
						.getGlowingEffectDistance()) {
					if (glowingHologram == null) {
						glowingHologram = new Hologram(gravesConfig.getLocation().clone().add(.5, -2, .5), "", false,
								true);
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

}
