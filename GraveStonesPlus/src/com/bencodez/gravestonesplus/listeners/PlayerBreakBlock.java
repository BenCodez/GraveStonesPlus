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
	public void onBlockBreak(BlockBreakEvent event) {
		if (event.getPlayer() != null) {
			if (event.getBlock().getType().equals(Material.PLAYER_HEAD)) {
				for (Grave grave : plugin.getGraves()) {
					if (grave.isGrave(event.getBlock())) {
						if (grave.isOwner(event.getPlayer())
								|| (event.getPlayer().hasPermission("GraveStonesPlus.BreakOtherGraves")
										&& plugin.getConfigFile().isBreakOtherGravesWithPermission())) {
							AdvancedCoreUser user = UserManager.getInstance().getUser(event.getPlayer());
							user.giveExp(grave.getGravesConfig().getExp());

							boolean notInCorrectSlot = false;
							for (Entry<Integer, ItemStack> item : grave.getGravesConfig().getItems().entrySet()) {
								PlayerInventory currentInv = event.getPlayer().getInventory();
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
							user.sendMessage("You broke your grave!");
							if (notInCorrectSlot) {
								user.sendMessage("Some items didn't return to their correct slot");
							}
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

	public boolean isSlotAvailable(ItemStack slot) {
		if (slot == null || slot.getType().isAir()) {
			return true;
		}
		return false;
	}
}
