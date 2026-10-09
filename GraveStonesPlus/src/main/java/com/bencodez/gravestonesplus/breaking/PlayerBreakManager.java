package com.bencodez.gravestonesplus.breaking;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.storage.GravesConfig;
import com.bencodez.simpleapi.messages.MessageAPI;
import com.bencodez.simpleapi.messages.actionbar.ActionBar;
import com.bencodez.simpleapi.time.ParsedDuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Manages timed grave breaking by non-owners using repeated left click hits.
 */
@Getter
@Setter
public class PlayerBreakManager {

	/**
	 * Cooldown after owner break completion to avoid immediate restart from
	 * trailing damage packets.
	 */
	private static final long OWNER_COMPLETION_RESTART_BLOCK_MS = 1000L;

	/**
	 * Plugin instance.
	 */
	private GraveStonesPlus plugin;

	/**
	 * Active break attempts by player UUID.
	 */
	private Map<UUID, ActiveGraveBreak> activeBreaks = new ConcurrentHashMap<UUID, ActiveGraveBreak>();

	/**
	 * Active break attempts for grave owners.
	 */
	private Map<UUID, ActiveGraveBreak> ownerActiveBreaks = new ConcurrentHashMap<UUID, ActiveGraveBreak>();

	/**
	 * Timestamp of last completed owner break per player to prevent immediate
	 * restarts.
	 */
	private Map<UUID, Long> ownerLastCompleted = new ConcurrentHashMap<UUID, Long>();

	/**
	 * Repeating cleanup and progress task.
	 */
	private BukkitTask task;
    private com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask ownedTask;
    private volatile long generation;
    private final java.util.concurrent.atomic.AtomicLong authorizationGeneration = new java.util.concurrent.atomic.AtomicLong();

	/**
	 * Creates the manager.
	 *
	 * @param plugin Plugin instance
	 */
	public PlayerBreakManager(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	/**
	 * Starts the repeating task.
	 */
	public void start() {
		stop();
        final long expected = ++generation;
        ownedTask = plugin.getBukkitScheduler().getFoliaLib().getImpl().runTimer(() -> {
            if (generation == expected) tick();
        }, 1L, 2L);
	}

	/**
	 * Stops the repeating task and clears active attempts.
	 */
	public void stop() {
        generation++;
        invalidateAuthorization();
        if (ownedTask != null) { ownedTask.cancel(); ownedTask = null; }
		if (task != null) {
			task.cancel();
			task = null;
		}
		activeBreaks.clear();
		ownerActiveBreaks.clear();
		ownerLastCompleted.clear();
	}

    /** Reload invalidates completed proofs as well as in-progress attempts. */
    public void invalidateAuthorization() {
        authorizationGeneration.incrementAndGet();
        activeBreaks.clear(); ownerActiveBreaks.clear(); ownerLastCompleted.clear();
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

		if (!eligible(player, grave) || !grave.legacyBreakAllowed(player)) {
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
			active.setAuthorizationGeneration(authorizationGeneration.get());
			activeBreaks.put(player.getUniqueId(), active);

			sendStartMessage(player);
			sendProgress(player, active);
			return false;
		}

		active.setLastHitTime(now);

		if ((now - active.getStartTime()) >= getRequiredBreakTime().getMillis()) {
			if (activeBreaks.remove(player.getUniqueId(), active)) completeBreak(player, grave, active);
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
        final long expected = generation;
        java.util.Set<UUID> players = new java.util.HashSet<>(activeBreaks.keySet()); players.addAll(ownerActiveBreaks.keySet());
        for (UUID uuid : players) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) { activeBreaks.remove(uuid); ownerActiveBreaks.remove(uuid); continue; }
            plugin.getBukkitScheduler().runTask(plugin, () -> { if (generation == expected) tickPlayer(player); }, player);
        }
    }
    private void tickPlayer(Player player) {
        UUID uuid = player.getUniqueId(); long now = System.currentTimeMillis();
        ActiveGraveBreak active = activeBreaks.get(uuid);
        if (active != null) {
            Grave grave = getGraveByKey(active.getGraveId());
            if (active.getAuthorizationGeneration() != authorizationGeneration.get() || !eligible(player, grave)
                    || (!plugin.canRecover(grave, player) && !grave.legacyBreakAllowed(player))) activeBreaks.remove(uuid, active);
            else if (isTimedOut(active, getHitTimeout(), now)) { sendCancelledMessage(player); activeBreaks.remove(uuid, active); }
            else if (plugin.getConfigFile().isBreakOtherGravesActionBarMessage()) sendProgress(player, active);
        }
        active = ownerActiveBreaks.get(uuid);
        if (active != null) {
            Grave grave = getGraveByKey(active.getGraveId());
            if (!eligible(player, grave) || !grave.isOwner(player)) ownerActiveBreaks.remove(uuid, active);
            else if (isTimedOut(active, getHitTimeoutOwner(), now)) { sendCancelledMessageOwner(player); ownerActiveBreaks.remove(uuid, active); }
            else {
                if (plugin.getConfigFile().isBreakOwnGraveActionBarMessage()) sendProgress(player, active);
                if (now - active.getStartTime() >= getRequiredBreakTimeOwner().getMillis()
                        && ownerActiveBreaks.remove(uuid, active)) { recordOwnerCompletion(player); completeOwnerBreak(player, grave); }
            }
        }
    }
    private boolean eligible(Player player, Grave grave) {
        return player.isOnline() && plugin.isLiveGrave(grave) && grave.getDistance(player) >= 0 && grave.getDistance(player) <= 7;
    }

	/**
	 * Handles a hit on an owner's grave. Uses BreakOwnGraves config values.
	 *
	 * @param player Player
	 * @param grave  Grave
	 * @return true if the grave was claimed by this hit
	 */
	public boolean handleOwnerHit(Player player, Grave grave) {
		if (player == null || grave == null || grave.getGravesConfig() == null) {
			return false;
		}

		if (!plugin.isLiveGrave(grave)) {
			return false;
		}

		long now = System.currentTimeMillis();

		Long last = ownerLastCompleted.get(player.getUniqueId());
		if (last != null) {
			long since = now - last.longValue();
			if (since < OWNER_COMPLETION_RESTART_BLOCK_MS) {
				// plugin.debug("Owner break: ignoring start due to recent completion (" + since
				// + "ms)");
				return false;
			}
		}

		String graveKey = buildGraveKey(grave);
		ActiveGraveBreak active = ownerActiveBreaks.get(player.getUniqueId());

		if (active == null || !graveKey.equals(active.getGraveId()) || isTimedOut(active, getHitTimeoutOwner(), now)) {
			active = new ActiveGraveBreak();
			active.setPlayerUUID(player.getUniqueId());
			active.setGraveId(graveKey);
			active.setStartTime(now);
			active.setLastHitTime(now);
			ownerActiveBreaks.put(player.getUniqueId(), active);

			// plugin.debug("Owner break: start for " + player.getName() + " grave=" +
			// graveKey + " start=" + now);
			sendStartMessageOwner(player);
			sendProgress(player, active);
			return false;
		}

		active.setLastHitTime(now);

		long required = getRequiredBreakTimeOwner().getMillis();
		long elapsed = now - active.getStartTime();
		// plugin.debug("Owner break: elapsed=" + elapsed + " required=" + required + "
		// for " + player.getName());
		if (elapsed >= required) {
			// plugin.debug("Owner break: completing for " + player.getName());

			ownerActiveBreaks.remove(player.getUniqueId());
			recordOwnerCompletion(player);

			completeOwnerBreak(player, grave);
			return true;
		}

		sendProgress(player, active);
		return false;
	}

	/**
	 * Cancels an owner break attempt.
	 *
	 * @param playerUUID Player UUID
	 */
	public void cancelOwnerBreak(UUID playerUUID) {
		ownerActiveBreaks.remove(playerUUID);
	}

	/**
	 * Sends owner break start message.
	 *
	 * @param player Player
	 */
	private void sendStartMessageOwner(Player player) {
		if (plugin.getConfigFile().isBreakOwnGraveSendMessage()) {
			player.sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatStartedBreakingGrave()));
		}
	}

	/**
	 * Sends owner break cancelled message.
	 *
	 * @param player Player
	 */
	private void sendCancelledMessageOwner(Player player) {
		if (plugin.getConfigFile().isBreakOwnGraveSendMessage()) {
			player.sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatStoppedBreakingGrave()));
		}
	}

	/**
	 * Completes an owner grave break by claiming the grave.
	 *
	 * @param player Player
	 * @param grave  Grave
	 */
	private void completeOwnerBreak(Player player, Grave grave) {
		if (grave == null || !plugin.isLiveGrave(grave)) {
			return;
		}

		ownerActiveBreaks.remove(player.getUniqueId());
		recordOwnerCompletion(player);

		// plugin.debug("Owner break: calling grave.claim for " + player.getName());
		grave.claim(player);
	}

	/**
	 * Records owner break completion and schedules cleanup of the completion
	 * timestamp.
	 *
	 * @param player Player
	 */
	private void recordOwnerCompletion(final Player player) {
		final UUID playerUUID = player.getUniqueId();
		ownerLastCompleted.put(playerUUID, System.currentTimeMillis());

		// Cleanup changes only UUID timestamps and must not depend on a live entity.
		plugin.getBukkitScheduler().runTaskLater(plugin, () -> {
			Long last = ownerLastCompleted.get(playerUUID);
			if (last != null) {
				long age = System.currentTimeMillis() - last.longValue();
				if (age >= OWNER_COMPLETION_RESTART_BLOCK_MS) {
					ownerLastCompleted.remove(playerUUID);
				}
			}

		}, 20L);
	}

	/**
	 * Gets the required owner break time.
	 *
	 * @return required break time
	 */
	private ParsedDuration getRequiredBreakTimeOwner() {
		return plugin.getConfigFile().getBreakOwnGraveTime();
	}

	/**
	 * Gets the owner break hit timeout.
	 *
	 * @return hit timeout
	 */
	private ParsedDuration getHitTimeoutOwner() {
		return plugin.getConfigFile().getBreakOwnGraveHitTimeout();
	}

	/**
	 * Completes a grave break by claiming the grave for the player.
	 *
	 * @param player Player
	 * @param grave  Grave
	 */
	private void completeBreak(Player player, Grave grave, ActiveGraveBreak active) {
		if (active.getAuthorizationGeneration() != authorizationGeneration.get() || !eligible(player, grave) || !grave.legacyBreakAllowed(player)) {
			return;
		}

		grave.claimAfterTimedBreak(player, new CompletedBreak(player, grave, active));

	}

    /** Only the manager can mint this proof after the timed hit path completes. */
    public final class CompletedBreak {
        private final UUID playerId;
        private final String graveId;
        private final long expectedGeneration, elapsedMillis;
        private CompletedBreak(Player player, Grave grave, ActiveGraveBreak active) {
            playerId = player.getUniqueId(); graveId = buildGraveKey(grave);
            expectedGeneration = active.getAuthorizationGeneration(); elapsedMillis = System.currentTimeMillis() - active.getStartTime();
        }
        public boolean allows(Player player, Grave grave) {
            return plugin.getPlayerBreakManager() == PlayerBreakManager.this
                    && authorizationGeneration.get() == expectedGeneration && playerId.equals(player.getUniqueId())
                    && graveId.equals(buildGraveKey(grave)) && elapsedMillis >= getRequiredBreakTime().getMillis()
                    && eligible(player, grave) && grave.legacyBreakAllowed(player);
        }
    }

	/**
	 * Sends a start message to the player.
	 *
	 * @param player Player
	 */
	private void sendStartMessage(Player player) {
		if (plugin.getConfigFile().isBreakOtherGravesSendMessage()) {
			player.sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatStartedBreakingGrave()));
		}
	}

	/**
	 * Sends a cancelled message to the player.
	 *
	 * @param player Player
	 */
	private void sendCancelledMessage(Player player) {
		if (plugin.getConfigFile().isBreakOtherGravesSendMessage()) {
			player.sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatStoppedBreakingGrave()));
		}
	}

	/**
	 * Sends progress in the action bar.
	 *
	 * @param player Player
	 * @param active Active break
	 */
	private void sendProgress(Player player, ActiveGraveBreak active) {
		boolean owner = ownerActiveBreaks.containsKey(player.getUniqueId());
		if (owner) {
			if (!plugin.getConfigFile().isBreakOwnGraveActionBarMessage()) {
				return;
			}
		} else {
			if (!plugin.getConfigFile().isBreakOtherGravesActionBarMessage()) {
				return;
			}
		}

		long now = System.currentTimeMillis();
		long required = owner ? getRequiredBreakTimeOwner().getMillis() : getRequiredBreakTime().getMillis();
		long elapsed = now - active.getStartTime();

		double percent = required <= 0L ? 1.0D : Math.min(1.0D, (double) elapsed / (double) required);
		int display = (int) Math.round(percent * 100.0D);

		ActionBar actionBar = new ActionBar(MessageAPI.colorize("&eBreaking grave: &6" + display + "%"), 60);
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
		return plugin.getConfigFile().getBreakOtherGravesTime();
	}

	/**
	 * Gets the hit timeout.
	 *
	 * @return hit timeout
	 */
	private ParsedDuration getHitTimeout() {
		return plugin.getConfigFile().getBreakOtherGravesHitTimeout();
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

		for (Grave grave : plugin.activeGraveSnapshot()) {
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

	/**
	 * Represents an active timed grave break attempt.
	 */
	@Getter
	@Setter
	public static class ActiveGraveBreak {

		/**
		 * Player UUID.
		 */
		private UUID playerUUID;

		/**
		 * Grave key.
		 */
		private String graveId;

		/**
		 * Start time in milliseconds.
		 */
		private long startTime;

		/**
		 * Last hit time in milliseconds.
		 */
		private long lastHitTime;
        private long authorizationGeneration;
	}
}
