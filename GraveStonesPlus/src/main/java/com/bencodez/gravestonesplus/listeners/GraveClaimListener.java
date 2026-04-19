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

	@Getter
	@Setter
	private GraveStonesPlus plugin;

	/**
	 * Tracks active held-left-click attempts.
	 */
	@Getter
	private final Map<UUID, Grave> mining = new HashMap<UUID, Grave>();

	/**
	 * Last time a player swung at a grave.
	 */
	@Getter
	private final Map<UUID, Long> lastSwing = new HashMap<UUID, Long>();

	/**
	 * Tracks the last grave handled for a player to avoid duplicate processing from
	 * multiple events firing for the same action.
	 */
	@Getter
	private final Map<UUID, Grave> lastHandledGrave = new HashMap<UUID, Grave>();

	/**
	 * Tracks the last handled time for a player to avoid duplicate processing from
	 * multiple events firing for the same action.
	 */
	@Getter
	private final Map<UUID, Long> lastHandledTime = new HashMap<UUID, Long>();

	/**
	 * Creates the listener.
	 * 
	 * @param plugin the plugin
	 */
	public GraveClaimListener(GraveStonesPlus plugin) {
		this.plugin = plugin;

		plugin.getBukkitScheduler().runTaskTimer(plugin, new Runnable() {

			@Override
			public void run() {
				long now = System.currentTimeMillis();

				for (UUID uuid : new HashMap<UUID, Grave>(mining).keySet()) {
					Player player = Bukkit.getPlayer(uuid);
					if (player == null) {
						clearPlayerState(uuid);
						continue;
					}

					Grave grave = mining.get(uuid);
					if (grave == null || !grave.isValid() || grave.getGravesConfig().isDestroyed()) {
						clearPlayerState(uuid);
						continue;
					}

					Long last = lastSwing.get(uuid);
					if (last == null || now - last > 400L) {
						clearPlayerState(uuid);
					}
				}
			}
		}, 5L, 5L);
	}

	/**
	 * Handles claiming or breaking logic for a grave.
	 * 
	 * @param grave the grave
	 * @param player the player
	 */
	public void handleClaiming(Grave grave, Player player) {
		if (grave == null || player == null) {
			return;
		}

		if (grave.getGravesConfig().isDestroyed()) {
			return;
		}

		if (grave.isOwner(player)) {
			if (!plugin.getConfigFile().isBreakOwnGraveRequireBreakTime()) {
				grave.claim(player);
				return;
			}

			plugin.getPlayerBreakManager().handleOwnerHit(player, grave);
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

		plugin.getPlayerBreakManager().handleHit(player, grave);
	}

	/**
	 * Handles left click swings. This is more reliable for barriers than
	 * PlayerInteractEvent.
	 * 
	 * @param event the event
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
		if (grave == null || !grave.isValid()) {
			return;
		}

		GraveInteractEvent ge = new GraveInteractEvent(grave, player, GraveInteractionType.BREAK_ATTEMPT);
		Bukkit.getPluginManager().callEvent(ge);
		if (ge.isCancelled()) {
			return;
		}

		if (isDuplicateAttempt(player, grave)) {
			return;
		}

		mining.put(player.getUniqueId(), grave);
		lastSwing.put(player.getUniqueId(), Long.valueOf(System.currentTimeMillis()));

		handleClaiming(grave, player);
	}

	/**
	 * Handles normal block interaction events.
	 * 
	 * @param event the event
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
		}
	}

	/**
	 * Cancels breaking of grave markers. Claim handling is intentionally done only
	 * through the animation path to avoid duplicate processing.
	 * 
	 * @param event the event
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

		event.setCancelled(true);

		if (!grave.isValid()) {
			return;
		}
	}

	/**
	 * Clears player state when they quit.
	 * 
	 * @param event the event
	 */
	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		clearPlayerState(event.getPlayer().getUniqueId());
		plugin.getPlayerBreakManager().cancelOwnerBreak(event.getPlayer().getUniqueId());
	}

	/**
	 * Handles right-click interaction with graves.
	 * 
	 * @param event the event
	 * @param grave the grave
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
	 * Gets a grave from a block.
	 * 
	 * @param block the block
	 * @return the grave or null if none exists
	 */
	private Grave getGraveFromBlock(Block block) {
		Object obj = MiscUtils.getInstance().getBlockMeta(block, "Grave");
		if (!(obj instanceof Grave)) {
			return null;
		}

		return (Grave) obj;
	}

	/**
	 * Checks whether the material is used as a grave marker.
	 * 
	 * @param type the material type
	 * @return true if the material is a grave marker
	 */
	private boolean isAnyGraveMarker(Material type) {
		return type == Material.PLAYER_HEAD || type == Material.BARRIER || type == Material.CHEST;
	}

	/**
	 * Checks whether this attempt is a duplicate of a very recent one for the same
	 * grave.
	 * 
	 * @param player the player
	 * @param grave the grave
	 * @return true if the attempt should be ignored
	 */
	private boolean isDuplicateAttempt(Player player, Grave grave) {
		UUID uuid = player.getUniqueId();
		long now = System.currentTimeMillis();

		Grave lastGrave = lastHandledGrave.get(uuid);
		Long lastTime = lastHandledTime.get(uuid);

		if (lastGrave != null && lastTime != null) {
			if (lastGrave == grave && now - lastTime.longValue() <= 150L) {
				lastSwing.put(uuid, Long.valueOf(now));
				return true;
			}
		}

		lastHandledGrave.put(uuid, grave);
		lastHandledTime.put(uuid, Long.valueOf(now));
		return false;
	}

	/**
	 * Clears tracked interaction state for a player.
	 * 
	 * @param uuid the player uuid
	 */
	private void clearPlayerState(UUID uuid) {
		mining.remove(uuid);
		lastSwing.remove(uuid);
		lastHandledGrave.remove(uuid);
		lastHandledTime.remove(uuid);
		plugin.getPlayerBreakManager().cancelBreak(uuid);
	}
}