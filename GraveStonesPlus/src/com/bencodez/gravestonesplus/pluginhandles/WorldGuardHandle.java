package com.bencodez.gravestonesplus.pluginhandles;

import org.bukkit.entity.Player;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;

import net.goldtreeservers.worldguardextraflags.flags.Flags;

public class WorldGuardHandle {
	@SuppressWarnings("unused")
	private GraveStonesPlus plugin;

	public WorldGuardHandle(GraveStonesPlus plugin) {
		this.plugin = plugin;

	}

	public boolean isKeepInv(Player player) {
		WorldGuardPlugin.inst().wrapPlayer(player);
		LocalPlayer localPlayer = WorldGuardPlugin.inst().wrapPlayer(player);
		ApplicableRegionSet regions = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery()
				.getApplicableRegions(localPlayer.getLocation());
		return regions.queryValue(localPlayer, Flags.KEEP_INVENTORY).booleanValue();
	}
}
