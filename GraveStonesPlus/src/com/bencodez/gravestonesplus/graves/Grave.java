package com.bencodez.gravestonesplus.graves;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import lombok.Getter;

public class Grave {

	@Getter
	private GravesConfig gravesConfig;

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

}
