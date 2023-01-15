package com.bencodez.gravestonesplus.pluginhandles;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.bencodez.gravestonesplus.GraveStonesPlus;

import me.NoChance.PvPManager.PvPManager;
import me.NoChance.PvPManager.PvPlayer;

public class PvpManagerHandle {
	private GraveStonesPlus plugin;
	private PvPManager pvpmanager;

	public PvpManagerHandle(GraveStonesPlus plugin) {
		this.plugin = plugin;
		// Check if PvPManager is enabled
		if (Bukkit.getPluginManager().isPluginEnabled("PvPManager")) {
			pvpmanager = (PvPManager) Bukkit.getPluginManager().getPlugin("PvPManager");
			this.plugin.getLogger().info("PvPManager support loaded");
		}
	}

	public boolean canHaveGrave(Player p) {
		if (pvpmanager != null) {
			return !PvPlayer.get(p).isInCombat();
		}
		return true;
	}
}
