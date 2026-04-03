package com.bencodez.gravestonesplus.listeners;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.RayTraceResult;

import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.events.GraveInteractEvent;
import com.bencodez.gravestonesplus.events.GraveInteractionType;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.simpleapi.messages.MessageAPI;

import lombok.Getter;
import lombok.Setter;

/**
 * Handles player claim and interaction logic for graves.
 */
@Getter
@Setter
public class GraveClaimListener implements Listener {

	private GraveStonesPlus plugin;

	/**
	 * Tracks active held-left-click attempts.
	 */
	private final Map<UUID, Grave> mining = new HashMap<>();

	/**
	 * Last time a player swung at a grave.
	 */
	private final Map<UUID, Long> lastSwing = new HashMap<>();

	public GraveClaimListener(GraveStonesPlus plugin) {
		this.plugin = plugin;

		Bukkit.getScheduler().runTaskTimer(plugin, () -> {
			long now = System.currentTimeMillis();

			for (UUID uuid : new HashMap<>(mining).keySet()) {
				Player player = Bukkit.getPlayer(uuid);
				if (player == null) {
					mining.remove(uuid);
					lastSwing.remove(uuid);
					plugin.getOtherPlayerBreakManager().cancelBreak(uuid);
					continue;
				}

				Grave grave = mining.get(uuid);
				if (grave == null || !grave.isValid() || grave.getGravesConfig().isDestroyed()) {
					mining.remove(uuid);
					lastSwing.remove(uuid);
					plugin.getOtherPlayerBreakManager().cancelBreak(uuid);
					continue;
				}

				Long last = lastSwing.get(uuid);
				if (last == null || now - last > 400L) {
					mining.remove(uuid);
					lastSwing.remove(uuid);
					plugin.getOtherPlayerBreakManager().cancelBreak(uuid);
				}
			}
		}, 5L, 5L);
	}

	public void handleCiaming(Grave grave, Player player) {
		if (grave.getGravesConfig().isDestroyed()) {
			return;
		}

		if (grave.isOwner(player)) {
			grave.claim(player);
			return;
		}

		if (!plugin.getConfigFile().isBreakOtherGravesEnabled()) {
			player.sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatNotYourGrave()));
			return;
		}

		if (!player.hasPermission("GraveStonesPlus.BreakOtherGraves")
				&& plugin.getConfigFile().isBreakOtherGravesRequirePermission()) {
			player.sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatNotEnoughPermission()));
			return;
		}

		if (!grave.canNonOwnerClaim()) {

			player.sendMessage(MessageAPI
					.colorize(MessageAPI.replacePlaceHolder(plugin.getConfigFile().getFormatUnableToClaimDelay(),
							"%time%", grave.getTimeUntilNonOwnerClaimFormatted())));
			return;
		}

		if (!plugin.getConfigFile().isBreakOtherGravesRequireBreakTime()) {
			grave.claim(player);
			return;
		}

		plugin.getOtherPlayerBreakManager().handleHit(player, grave);
	}

	/**
	 * Handles left click swings. This is more reliable for barriers than
	 * PlayerInteractEvent.
	 */
	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
	public void onPlayerAnimation(PlayerAnimationEvent event) {
		Player player = event.getPlayer();

		RayTraceResult result = player.getWorld().rayTraceBlocks(player.getEyeLocation(),
				player.getEyeLocation().getDirection(), 6.0, FluidCollisionMode.NEVER, true);

		if (result == null || result.getHitBlock() == null) {
			return;
		}

		Block block = result.getHitBlock();
		if (!isAnyGraveMarker(block.getType())) {
			return;
		}

		Grave grave = getGraveFromBlock(block);
		if (grave == null) {
			return;
		}

		if (!grave.isValid()) {
			return;
		}

		GraveInteractEvent ge = new GraveInteractEvent(grave, player, GraveInteractionType.BREAK_ATTEMPT);
		Bukkit.getPluginManager().callEvent(ge);
		if (ge.isCancelled()) {
			return;
		}

		mining.put(player.getUniqueId(), grave);
		lastSwing.put(player.getUniqueId(), System.currentTimeMillis());

		handleCiaming(grave, player);
	}

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
		}
	}

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
		handleCiaming(grave, event.getPlayer());
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		UUID uuid = event.getPlayer().getUniqueId();
		mining.remove(uuid);
		lastSwing.remove(uuid);
		plugin.getOtherPlayerBreakManager().cancelBreak(uuid);
	}

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

	private Grave getGraveFromBlock(Block block) {
		Object obj = MiscUtils.getInstance().getBlockMeta(block, "Grave");
		if (!(obj instanceof Grave)) {
			return null;
		}

		return (Grave) obj;
	}

	private boolean isAnyGraveMarker(Material type) {
		return type == Material.PLAYER_HEAD || type == Material.BARRIER || type == Material.CHEST;
	}
}