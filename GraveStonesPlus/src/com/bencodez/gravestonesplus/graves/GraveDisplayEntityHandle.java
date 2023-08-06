package com.bencodez.gravestonesplus.graves;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;

import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.gravestonesplus.GraveStonesPlus;

public class GraveDisplayEntityHandle {
	@SuppressWarnings("unused")
	private GraveStonesPlus plugin;

	public GraveDisplayEntityHandle(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	@SuppressWarnings("deprecation")
	public ItemDisplay create(Grave grave) {
		Location loc = grave.getGravesConfig().getLocation();
		Location newLoc = new Location(loc.getWorld(), loc.getBlockX()+.5, loc.getBlockY()+.5, loc.getBlockZ()+.5);
		ItemDisplay display = grave.getGravesConfig().getLocation().getWorld().spawn(newLoc, ItemDisplay.class);
		display.setItemStack(new ItemBuilder(Material.PLAYER_HEAD)
				.setSkullOwner(Bukkit.getOfflinePlayer(grave.getGravesConfig().getUuid())).toItemStack());
		display.setVisibleByDefault(true);
		MiscUtils.getInstance().setEntityMeta(display, "Grave", grave);
		return display;
	}

	public ItemDisplay get(Grave grave) {
		for (Entity entity : grave.getGravesConfig().getLocation().getWorld()
				.getNearbyEntities(grave.getGravesConfig().getLocation(), 2, 2, 2)) {
			if (entity.getType().equals(EntityType.ITEM_DISPLAY)) {
				ItemDisplay display = (ItemDisplay) entity;
				if (display.getItemStack().getType().equals(Material.PLAYER_HEAD)) {
					UUID uuid = display.getUniqueId();
					if (grave.getGravesConfig().getGraveUUID().equals(uuid)) {
						MiscUtils.getInstance().setEntityMeta(display, "Grave", grave);
						return display;
					}

				}
			}
		}
		return null;
	}
}
