package com.bencodez.gravestonesplus.listeners;

import java.util.Map.Entry;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.advancedcore.api.user.UserManager;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;

// TODO: Auto-generated Javadoc
/**
 * The Class PlayerInteract.
 */
public class PlayerBreakBlock implements Listener {

	/** The plugin. */
	private GraveStonesPlus plugin;

	/**
	 * Instantiates a new player interact.
	 *
	 * @param plugin the plugin
	 */
	public PlayerBreakBlock(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	/**
	 * On player interact.
	 *
	 * @param event the event
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlayerInteract(BlockBreakEvent event) {
		if (event.getPlayer() != null) {
			if (event.getBlock().getType().equals(Material.PLAYER_HEAD)) {
				for (Grave grave : plugin.getGraves()) {
					if (grave.isGrave(event.getBlock())) {
						if (grave.isOwner(event.getPlayer())) {
							AdvancedCoreUser user = UserManager.getInstance().getUser(event.getPlayer());
							user.giveExp(grave.getGravesConfig().getExp());

							for (Entry<Integer, ItemStack> item : grave.getGravesConfig().getItems().entrySet()) {
								PlayerInventory currentInv = event.getPlayer().getInventory();
								if (item.getKey().intValue() >= 0) {
									ItemStack currentItem = currentInv.getItem(item.getKey().intValue());
									if (currentItem == null) {
										currentInv.setItem(item.getKey().intValue(), item.getValue());
									} else {
										user.giveItem(item.getValue());
									}
								} else {
									switch (item.getKey().intValue()) {
									case -1:
										if (currentInv.getHelmet() == null) {
											currentInv.setHelmet(item.getValue());
										} else {
											user.giveItem(item.getValue());
										}
										break;
									case -2:
										if (currentInv.getChestplate() == null) {
											currentInv.setChestplate(item.getValue());
										} else {
											user.giveItem(item.getValue());
										}
										break;
									case -3:
										if (currentInv.getLeggings() == null) {
											currentInv.setLeggings(item.getValue());
										} else {
											user.giveItem(item.getValue());
										}
										break;
									case -4:
										if (currentInv.getBoots() == null) {
											currentInv.setBoots(item.getValue());
										} else {
											user.giveItem(item.getValue());
										}
										break;
									case -5:
										if (currentInv.getItemInOffHand() == null) {
											currentInv.setItemInOffHand(item.getValue());
										} else {
											user.giveItem(item.getValue());
										}
										break;
									}
								}
							}
							user.sendMessage("You broke your grave!");
							grave.removeHologram();
							plugin.removeGrave(grave);
							return;
						}
						event.getPlayer().sendMessage("Not your grave!");
						return;
					}
				}
			}
		}
	}
}
