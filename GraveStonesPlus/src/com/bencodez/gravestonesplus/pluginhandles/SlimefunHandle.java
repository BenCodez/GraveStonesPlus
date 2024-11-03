package com.bencodez.gravestonesplus.pluginhandles;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.Soulbound;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

public class SlimefunHandle {

	private GraveStonesPlus plugin;
	@SuppressWarnings("unused")
	private Slimefun slimefun;

	public SlimefunHandle(GraveStonesPlus plugin) {
		this.plugin = plugin;
		// Check if PvPManager is enabled
		if (Bukkit.getPluginManager().isPluginEnabled("Slimefun")) {
			slimefun = (Slimefun) Bukkit.getPluginManager().getPlugin("Slimefun");
			this.plugin.getLogger().info("Slimefun support loaded");
		}
	}

	public boolean isSoulBoundItem(ItemStack item) {
		SlimefunItem slimefunItem = SlimefunItem.getByItem(item);
		return slimefunItem instanceof Soulbound;
	}
}
