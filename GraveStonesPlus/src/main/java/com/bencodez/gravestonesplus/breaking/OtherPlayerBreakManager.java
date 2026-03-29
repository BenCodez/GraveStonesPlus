package com.bencodez.gravestonesplus.breaking;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import com.bencodez.advancedcore.api.misc.effects.ActionBar;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.storage.GravesConfig;
import com.bencodez.simpleapi.messages.MessageAPI;
import com.bencodez.simpleapi.time.ParsedDuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Manages timed grave breaking by non-owners using repeated left click hits.
 */
@Getter
@Setter
public class OtherPlayerBreakManager {

	/**
	 * Plugin instance.
	 */
	private GraveStonesPlus plugin;

	/**
	 * Active break attempts by player UUID.
	 */
	private Map<UUID, ActiveGraveBreak> activeBreaks = new ConcurrentHashMap<UUID, ActiveGraveBreak>();

	/**
	 * Repeating cleanup and progress task.
	 */
	private BukkitTask task;

	/**
	 * Creates the manager.
	 *
	 * @param plugin Plugin instance
	 */
	public OtherPlayerBreakManager(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	/**
	 * Starts the repeating task.
	 */
	public void start() {
		stop();
		task = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {

			@Override
			public void run() {
				tick();
			}
		}, 1L, 2L);
	}

	/**
	 * Stops the repeating task and clears active attempts.
	 */
	public void stop() {
		if (task != null) {
			task.cancel();
			task = null;
		}
		activeBreaks.clear();
	}

	/**
	 * Handles a hit on a grave by a player.
	 *
	 * If the player is already breaking the same grave and the timeout has not
	 * expired, the existing attempt is refreshed. Otherwise a new attempt is
	 * started.
	 *
	 * @param player Player
	 * @param grave  Grave
	 * @return true if the grave was claimed by this hit
	 */
	public boolean handleHit(Player player, Grave grave) {
		if (player == null || grave == null || grave.getGravesConfig() == null) {
			return false;
		}

		long now = System.currentTimeMillis();
		String graveKey = buildGraveKey(grave);
		ActiveGraveBreak active = activeBreaks.get(player.getUniqueId());

		if (active == null || !graveKey.equals(active.getGraveId()) || isTimedOut(active, getHitTimeout(), now)) {
			active = new ActiveGraveBreak();
			active.setPlayerUUID(player.getUniqueId());
			active.setGraveId(graveKey);
			active.setStartTime(now);
			active.setLastHitTime(now);
			activeBreaks.put(player.getUniqueId(), active);

			sendStartMessage(player);
			sendProgress(player, active);
			return false;
		}

		active.setLastHitTime(now);

		if ((now - active.getStartTime()) >= getRequiredBreakTime().getMillis()) {
			completeBreak(player, grave);
			activeBreaks.remove(player.getUniqueId());
			return true;
		}

		sendProgress(player, active);
		return false;
	}

	/**
	 * Cancels an active break attempt for a player.
	 *
	 * @param playerUUID Player UUID
	 */
	public void cancelBreak(UUID playerUUID) {
		activeBreaks.remove(playerUUID);
	}

	/**
	 * Checks whether a player is actively breaking a specific grave.
	 *
	 * @param player Player
	 * @param grave  Grave
	 * @return true if the player is actively breaking that grave
	 */
	public boolean isBreaking(Player player, Grave grave) {
		if (player == null || grave == null) {
			return false;
		}

		ActiveGraveBreak active = activeBreaks.get(player.getUniqueId());
		if (active == null) {
			return false;
		}

		return buildGraveKey(grave).equals(active.getGraveId());
	}

	/**
	 * Gets the elapsed break time for a player in milliseconds.
	 *
	 * @param playerUUID Player UUID
	 * @return elapsed time in milliseconds
	 */
	public long getElapsed(UUID playerUUID) {
		ActiveGraveBreak active = activeBreaks.get(playerUUID);
		if (active == null) {
			return 0L;
		}
		return System.currentTimeMillis() - active.getStartTime();
	}

	/**
	 * Gets the progress from 0.0 to 1.0 for a player.
	 *
	 * @param playerUUID Player UUID
	 * @return progress value
	 */
	public double getProgress(UUID playerUUID) {
		ActiveGraveBreak active = activeBreaks.get(playerUUID);
		if (active == null) {
			return 0.0D;
		}

		long required = getRequiredBreakTime().getMillis();
		if (required <= 0L) {
			return 1.0D;
		}

		double value = (double) (System.currentTimeMillis() - active.getStartTime()) / (double) required;
		if (value < 0.0D) {
			value = 0.0D;
		}
		if (value > 1.0D) {
			value = 1.0D;
		}
		return value;
	}

	/**
	 * Performs cleanup and optional progress updates.
	 */
	private void tick() {
		long now = System.currentTimeMillis();
		ParsedDuration timeout = getHitTimeout();

		Iterator<Map.Entry<UUID, ActiveGraveBreak>> iterator = activeBreaks.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, ActiveGraveBreak> entry = iterator.next();
			UUID playerUUID = entry.getKey();
			ActiveGraveBreak active = entry.getValue();

			Player player = Bukkit.getPlayer(playerUUID);
			if (player == null || !player.isOnline()) {
				iterator.remove();
				continue;
			}

			Grave grave = getGraveByKey(active.getGraveId());
			if (grave == null) {
				iterator.remove();
				continue;
			}

			if (!grave.isValid() || grave.getGravesConfig().isDestroyed()) {
				iterator.remove();
				continue;
			}

			if (isTimedOut(active, timeout, now)) {
				sendCancelledMessage(player);
				iterator.remove();
				continue;
			}

			if (plugin.getConfigFile().isBreakOtherGravesActionBarMessage()) {
				sendProgress(player, active);
			}
		}
	}

	/**
	 * Completes a grave break by claiming the grave for the player.
	 *
	 * @param player Player
	 * @param grave  Grave
	 */
	private void completeBreak(Player player, Grave grave) {
		if (grave == null || !grave.isValid() || grave.getGravesConfig().isDestroyed()) {
			return;
		}

		grave.claim(player);

		if (plugin.getConfigFile().isBreakOtherGravesSendMessage()) {
			player.sendMessage(MessageAPI.colorize("&aYou broke the grave."));
		}
	}

	/**
	 * Sends a start message to the player.
	 *
	 * @param player Player
	 */
	private void sendStartMessage(Player player) {
		if (plugin.getConfigFile().isBreakOtherGravesSendMessage()) {
			player.sendMessage(MessageAPI.colorize("&eStarted breaking grave. Keep hitting it."));
		}
	}

	/**
	 * Sends a cancelled message to the player.
	 *
	 * @param player Player
	 */
	private void sendCancelledMessage(Player player) {
		if (plugin.getConfigFile().isBreakOtherGravesSendMessage()) {
			player.sendMessage(MessageAPI.colorize("&cStopped breaking grave."));
		}
	}

	/**
	 * Sends progress in the action bar.
	 *
	 * @param player Player
	 * @param active Active break
	 */
	private void sendProgress(Player player, ActiveGraveBreak active) {
		if (!plugin.getConfigFile().isBreakOtherGravesActionBarMessage()) {
			return;
		}

		long now = System.currentTimeMillis();
		long required = getRequiredBreakTime().getMillis();
		long elapsed = now - active.getStartTime();

		double percent = required <= 0L ? 1.0D : Math.min(1.0D, (double) elapsed / (double) required);
		int display = (int) Math.round(percent * 100.0D);

		ActionBar actionBar = new ActionBar(MessageAPI.colorize("&eBreaking grave: &6" + display + "%"), 3);
		actionBar.send(player);
	}

	/**
	 * Checks whether an active break attempt has timed out.
	 *
	 * @param active  Active break
	 * @param timeout Timeout duration
	 * @param now     Current time in milliseconds
	 * @return true if timed out
	 */
	private boolean isTimedOut(ActiveGraveBreak active, ParsedDuration timeout, long now) {
		return (now - active.getLastHitTime()) > timeout.getMillis();
	}

	/**
	 * Gets the required break time.
	 *
	 * @return required break time
	 */
	private ParsedDuration getRequiredBreakTime() {
		return plugin.getConfigFile().getBreakOtherGravesTimeParsed();
	}

	/**
	 * Gets the hit timeout.
	 *
	 * @return hit timeout
	 */
	private ParsedDuration getHitTimeout() {
		return ParsedDuration.ofMillis(6000);
	}

	/**
	 * Finds an active grave by its generated key.
	 *
	 * @param graveKey Grave key
	 * @return Grave or null if not found
	 */
	private Grave getGraveByKey(String graveKey) {
		if (graveKey == null) {
			return null;
		}

		for (Grave grave : plugin.getGraves()) {
			if (grave != null && grave.getGravesConfig() != null && graveKey.equals(buildGraveKey(grave))) {
				return grave;
			}
		}

		return null;
	}

	/**
	 * Builds a stable key for a grave.
	 *
	 * @param grave Grave
	 * @return grave key
	 */
	private String buildGraveKey(Grave grave) {
		GravesConfig cfg = grave.getGravesConfig();
		return cfg.getUuid().toString() + "|" + cfg.getLocation().getWorld().getUID().toString() + "|"
				+ cfg.getLocation().getBlockX() + "," + cfg.getLocation().getBlockY() + ","
				+ cfg.getLocation().getBlockZ() + "|" + cfg.getTime();
	}
}