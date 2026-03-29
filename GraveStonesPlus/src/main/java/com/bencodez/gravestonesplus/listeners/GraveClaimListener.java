package com.bencodez.gravestonesplus.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.events.GraveInteractEvent;
import com.bencodez.gravestonesplus.events.GraveInteractionType;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GraveDisplayType;

import lombok.Getter;
import lombok.Setter;

/**
 * Handles player claim and interaction logic for graves.
 *
 * This listener is responsible for: - Right click grave interaction - Left
 * click owner claim logic - Left click timed breaking for non-owners -
 * Preventing vanilla block breaking as a safety net
 */
@Getter
@Setter
public class GraveClaimListener implements Listener {

	/**
	 * Plugin instance.
	 */
	private GraveStonesPlus plugin;

	/**
	 * Constructor.
	 *
	 * @param plugin Plugin instance
	 */
	public GraveClaimListener(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	/**
	 * Handles grave interaction from player clicks.
	 *
	 * @param event Player interact event
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlayerInteract(PlayerInteractEvent event) {
		Block clicked = event.getClickedBlock();
		if (clicked == null) {
			return;
		}

		if (!isAnyGraveMarker(clicked.getType())) {
			return;
		}

		Grave grave = getGraveFromBlock(clicked);
		if (grave == null) {
			return;
		}

		if (!grave.isValid()) {
			event.setCancelled(true);
			return;
		}

		Action action = event.getAction();
		if (action == Action.RIGHT_CLICK_BLOCK) {
			handleRightClick(event, grave);
			return;
		}

		if (action == Action.LEFT_CLICK_BLOCK) {
			event.setCancelled(true);
			handleLeftClick(event, grave);
		}
	}

	/**
	 * Safety net to prevent vanilla block breaking for grave markers.
	 *
	 * This also acts as an optional fallback trigger for timed breaking in case the
	 * interact event path does not run as expected.
	 *
	 * @param event Block break event
	 */
	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onBlockBreak(BlockBreakEvent event) {
		Block block = event.getBlock();
		if (!isAnyGraveMarker(block.getType())) {
			return;
		}

		Grave grave = getGraveFromBlock(block);
		if (grave == null) {
			return;
		}

		if (!grave.isValid()) {
			event.setCancelled(true);
			return;
		}

		GraveInteractEvent ge = new GraveInteractEvent(grave, event.getPlayer(), GraveInteractionType.BREAK_ATTEMPT);
		Bukkit.getPluginManager().callEvent(ge);
		if (ge.isCancelled()) {
			event.setCancelled(true);
			return;
		}

		event.setCancelled(true);

		if (grave.getGravesConfig().isDestroyed()) {
			return;
		}

		if (grave.isOwner(event.getPlayer())) {
			grave.claim(event.getPlayer());
			return;
		}

		if (isBarrierDisplayGrave(block, grave)) {
			return;
		}

		if (!event.getPlayer().hasPermission("GraveStonesPlus.BreakOtherGraves")) {
			event.getPlayer().sendMessage(plugin.getConfigFile().getFormatNotYourGrave());
			return;
		}

		if (plugin.getConfigFile().isBreakOtherGravesWithPermission()) {
			grave.claim(event.getPlayer());
			return;
		}

		if (!plugin.getConfigFile().isBreakOtherGravesEnabled()) {
			event.getPlayer().sendMessage(plugin.getConfigFile().getFormatNotYourGrave());
			return;
		}

		plugin.getOtherPlayerBreakManager().handleHit(event.getPlayer(), grave);
	}

	/**
	 * Clears any active timed break attempt when the player quits.
	 *
	 * @param event Player quit event
	 */
	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		plugin.getOtherPlayerBreakManager().cancelBreak(event.getPlayer().getUniqueId());
	}

	/**
	 * Handles right click behavior on a grave.
	 *
	 * @param event Player interact event
	 * @param grave Grave
	 */
	private void handleRightClick(PlayerInteractEvent event, Grave grave) {
		GraveInteractEvent ge = new GraveInteractEvent(grave, event.getPlayer(), GraveInteractionType.RIGHT_CLICK);
		Bukkit.getPluginManager().callEvent(ge);
		if (ge.isCancelled()) {
			event.setCancelled(true);
			return;
		}

		long lastClick = grave.getLastClick();
		long now = System.currentTimeMillis();
		grave.setLastClick(now);

		if (now - lastClick <= 500L) {
			event.setCancelled(true);
			return;
		}

		if (event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.CHEST) {
			event.setCancelled(true);
		}

		grave.onClick(event.getPlayer());
	}

	/**
	 * Handles left click behavior on a grave.
	 *
	 * @param event Player interact event
	 * @param grave Grave
	 */
	private void handleLeftClick(PlayerInteractEvent event, Grave grave) {
		GraveInteractEvent ge = new GraveInteractEvent(grave, event.getPlayer(), GraveInteractionType.LEFT_CLICK);
		Bukkit.getPluginManager().callEvent(ge);
		if (ge.isCancelled()) {
			event.setCancelled(true);
			return;
		}

		if (grave.getGravesConfig().isDestroyed()) {
			return;
		}

		Block clicked = event.getClickedBlock();
		if (clicked == null) {
			return;
		}

		if (grave.isOwner(event.getPlayer())) {
			grave.claim(event.getPlayer());
			return;
		}

		if (isBarrierDisplayGrave(clicked, grave)) {
			event.getPlayer().sendMessage(plugin.getConfigFile().getFormatNotYourGrave());
			return;
		}

		if (!event.getPlayer().hasPermission("GraveStonesPlus.BreakOtherGraves")) {
			plugin.debug("No permission to break other graves");
			event.getPlayer().sendMessage(plugin.getConfigFile().getFormatNotYourGrave());
			return;
		}

		if (plugin.getConfigFile().isBreakOtherGravesWithPermission()) {
			grave.claim(event.getPlayer());
			return;
		}

		if (!plugin.getConfigFile().isBreakOtherGravesEnabled()) {
			event.getPlayer().sendMessage(plugin.getConfigFile().getFormatNotYourGrave());
			return;
		}

		plugin.getOtherPlayerBreakManager().handleHit(event.getPlayer(), grave);
	}

	/**
	 * Gets a grave from a block's metadata.
	 *
	 * @param block Block
	 * @return Grave or null
	 */
	private Grave getGraveFromBlock(Block block) {
		Object obj = MiscUtils.getInstance().getBlockMeta(block, "Grave");
		if (!(obj instanceof Grave)) {
			return null;
		}

		return (Grave) obj;
	}

	/**
	 * Checks whether a material is any supported grave marker.
	 *
	 * @param type Material
	 * @return true if supported grave marker
	 */
	private boolean isAnyGraveMarker(Material type) {
		return type == Material.PLAYER_HEAD || type == Material.BARRIER || type == Material.CHEST;
	}

	/**
	 * Checks whether a block is a barrier for a display-entity grave.
	 *
	 * Those are treated as non-claimable by breaking.
	 *
	 * @param block Block
	 * @param grave Grave
	 * @return true if display barrier grave
	 */
	private boolean isBarrierDisplayGrave(Block block, Grave grave) {
		if (block.getType() != Material.BARRIER) {
			return false;
		}

		GraveDisplayType type = plugin.getConfigFile().getGraveDisplayTypeEnum();
		try {
			String stored = grave.getGravesConfig().getGraveDisplayType();
			if (stored != null && !stored.trim().isEmpty()) {
				type = GraveDisplayType.valueOf(stored.trim().toUpperCase());
			}
		} catch (Exception ignored) {
		}

		return type == GraveDisplayType.DISPLAY_ENTITY;
	}
}