package com.bencodez.gravestonesplus.graves;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Skull;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CompassMeta;
import org.bukkit.persistence.PersistentDataType;

import com.bencodez.advancedcore.api.hologram.Hologram;
import com.bencodez.advancedcore.api.inventory.BInventory;
import com.bencodez.advancedcore.api.inventory.BInventory.ClickEvent;
import com.bencodez.advancedcore.api.inventory.BInventoryButton;
import com.bencodez.advancedcore.api.item.ItemBuilder;
import com.bencodez.advancedcore.api.messages.PlaceholderUtils;
import com.bencodez.advancedcore.api.misc.MiscUtils;
import com.bencodez.advancedcore.api.user.AdvancedCoreUser;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.events.GraveClaimEvent;
import com.bencodez.gravestonesplus.events.GraveRemoveEvent;
import com.bencodez.gravestonesplus.events.GraveRemoveReason;
import com.bencodez.gravestonesplus.storage.GravesConfig;
import com.bencodez.simpleapi.messages.MessageAPI;
import com.bencodez.simpleapi.time.ParsedDuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Represents a single grave instance in the world.
 */
public class Grave {

	@Getter
	private final GravesConfig gravesConfig;

	@Getter
	private Hologram topHologram;

	@Getter
	private Hologram middleHologram;

	@Getter
	private Hologram bottomHologram;

	@Getter
	private Hologram glowingHologram;

	/**
	 * Scheduled expiration task for this grave.
	 */
	private ScheduledFuture<?> expirationTask;
    private long expirationGeneration;

	@Getter
	@Setter
	private long lastClick = 0;

	private final GraveStonesPlus plugin;

	@Getter
	private volatile boolean remove = false;
    private volatile boolean claimInProgress;
    @Getter private volatile boolean limitPayoutPending;
    private java.lang.ref.WeakReference<Player> limitRecipient = new java.lang.ref.WeakReference<>(null);
    private volatile long expiryDeadline;
    private GraveMarkerCleanup.Ticket cleanupTicket;

	@Getter
	private volatile ItemDisplay itemDisplay;

	private boolean chunkLoaded = false;

	/**
	 * Constructor.
	 *
	 * @param plugin       Plugin instance
	 * @param gravesConfig Stored grave config
	 */
	public Grave(GraveStonesPlus plugin, GravesConfig gravesConfig) {
		this.plugin = plugin;
		this.gravesConfig = gravesConfig;
	}

	/**
	 * Sets metadata for quick lookup.
	 *
	 * @param block Block
	 */
	public void loadBlockMeta(Block block) {
		block.removeMetadata("Grave", plugin);
        block.setMetadata("Grave", new org.bukkit.metadata.FixedMetadataValue(plugin, this));
        GraveMarkerCleanup.tag(plugin, block, GraveIdentity.of(gravesConfig));
	}

	/**
	 * Calls a Bukkit event on the main thread. If called off-thread, it schedules
	 * the call for next tick.
	 *
	 * @param event Event
	 */
	private void callEventSync(final Event event) {
		if (event == null) {
			return;
		}
		if (Bukkit.isPrimaryThread()) {
			Bukkit.getPluginManager().callEvent(event);
			return;
		}
		plugin.getBukkitScheduler().runTask(plugin, () -> {
			Bukkit.getPluginManager().callEvent(event);
		});
	}

	/**
	 * Loads the chunk for this grave and releases it later.
	 *
	 * @param task If true, run chunk load in a scheduled task
	 */
	public void loadChunk(boolean task) {
		if (task) {
			plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

				@Override
				public void run() {
					Block block = gravesConfig.getLocation().getBlock();
					if (!block.getChunk().isForceLoaded()) {
						block.getChunk().setForceLoaded(true);
						block.getChunk().load(false);
						chunkLoaded = true;
						unLoadChunk();
					}
				}
			}, gravesConfig.getLocation());
		} else {
			Block block = gravesConfig.getLocation().getBlock();
			if (!block.getChunk().isForceLoaded()) {
				block.getChunk().setForceLoaded(true);
				block.getChunk().load(false);
				chunkLoaded = true;
				unLoadChunk();
			}
		}
	}

	private void unLoadChunk() {
		plugin.getBukkitScheduler().runTaskLater(plugin, new Runnable() {

			@Override
			public void run() {
				if (chunkLoaded) {
					Block block = gravesConfig.getLocation().getBlock();
					block.getChunk().setForceLoaded(false);
					chunkLoaded = false;
				}
			}
		}, 20 * 6, gravesConfig.getLocation());
	}

	/**
	 * Gets the display type stored on this grave, falling back to global config if
	 * missing/invalid.
	 *
	 * @return Grave display type
	 */
	private GraveDisplayType getStoredDisplayTypeOrDefault() {
		try {
			String raw = gravesConfig.getGraveDisplayType();
			if (raw != null && !raw.trim().isEmpty()) {
				return GraveDisplayType.valueOf(raw.trim().toUpperCase());
			}
		} catch (Exception ignored) {
			// fall back below
		}
		return plugin.getConfigFile().getGraveDisplayTypeEnum();
	}

	/**
	 * Stores the display type on the grave config for persistence.
	 *
	 * @param type Type to store
	 */
	private void storeDisplayType(GraveDisplayType type) {
		if (type == null) {
			return;
		}
		try {
			gravesConfig.setGraveDisplayType(type.name());
		} catch (Exception ignored) {
			// best-effort
		}
	}

	/**
	 * Creates the grave marker in the world based on the stored display type.
	 */
	public void createGrave() {
		final Grave grave = this;

		final GraveDisplayType typeToUse = getStoredDisplayTypeOrDefault();
		storeDisplayType(typeToUse);

		switch (typeToUse) {
		case CHEST:
			plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

				@Override
				public void run() {
					loadChunk(false);

					Block block = grave.getGravesConfig().getLocation().getBlock();
					block.setType(Material.CHEST);

					// Fake chest only: cosmetic name, do not store items in chest inventory
					if (block.getState() instanceof Chest) {
						Chest chest = (Chest) block.getState();
						String name = "&3" + grave.getGravesConfig().getPlayerName() + "'s Grave";
						chest.setCustomName(MessageAPI.colorize(name));
						chest.update(true);
					}

					loadBlockMeta(block);
				}
			}, gravesConfig.getLocation());
			break;

		case DISPLAY_ENTITY:
			plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

				@Override
				public void run() {
					loadChunk(false);

					Block block = grave.getGravesConfig().getLocation().getBlock();

					itemDisplay = plugin.getGraveDisplayEntityHandler().createDisplay(grave);
					if (itemDisplay != null) {
						getGravesConfig().setDisplayUUID(itemDisplay.getUniqueId());
						itemDisplay.getPersistentDataContainer().set(plugin.getKey(), PersistentDataType.INTEGER, 1);
                        itemDisplay.getPersistentDataContainer().set(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING, GraveIdentity.of(gravesConfig));
					}

					block.setType(Material.BARRIER);
					loadBlockMeta(block);
				}
			}, gravesConfig.getLocation());
			break;

		case PLAYER_HEAD:
		default:
			plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

				@Override
				public void run() {
					loadChunk(false);

					Block block = grave.getGravesConfig().getLocation().getBlock();
					block.setType(Material.PLAYER_HEAD);
					if (block.getState() instanceof Skull) {
						Skull skull = (Skull) block.getState();
						skull.setOwningPlayer(Bukkit.getOfflinePlayer(getGravesConfig().getUuid()));
						skull.update();
					}
					loadBlockMeta(block);
				}
			}, gravesConfig.getLocation());
			break;
		}
	}

	/**
	 * Removes the world marker block (legacy method name kept).
	 */
	public void removeSkull() {
		plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				Block block = gravesConfig.getLocation().getBlock();
				block.setType(Material.AIR);
			}
		}, gravesConfig.getLocation());
	}

	/**
	 * Checks ownership.
	 *
	 * @param player Player
	 * @return True if owner
	 */
	public boolean isOwner(Player player) {
		return gravesConfig.getUuid().equals(player.getUniqueId());
	}

	/**
	 * Click message handler.
	 *
	 * @param player Player
	 */
	public void onClick(Player player) {
		HashMap<String, String> placeholders = new HashMap<String, String>();
		placeholders.put("player", gravesConfig.getPlayerName());
		placeholders.put("time", "" + new Date(gravesConfig.getTime()));
		placeholders.put("reason", gravesConfig.getDeathMessage());
		player.sendMessage(MessageAPI.colorize(
				PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatClickMessage(), placeholders)));
	}

	/**
	 * Removes nearby hologram entities associated with the grave.
	 */
    public void removeHologramsAround() {
        try {
            removeHologram();
            Location center = gravesConfig.getLocation().getBlock().getLocation().clone().add(.5, 0, .5);
            String identity = GraveIdentity.of(gravesConfig);
            for (Entity entity : center.getWorld().getNearbyEntities(center, 1, 3, 1)) {
                // Nearby entities can straddle region boundaries. Inspect and remove on their owner.
                plugin.getBukkitScheduler().runTask(plugin, () -> reconcileSurvivingHologram(entity, center, identity), entity);
            }
        } catch (Exception failure) { plugin.debug(failure); }
    }

    private void reconcileSurvivingHologram(Entity entity, Location center, String identity) {
        if (!plugin.isEnabled()) return;
        if (entity.getType() != EntityType.ARMOR_STAND && entity.getType() != EntityType.ITEM_DISPLAY) return;
        // Restoration may adopt this display after scheduling nearby cleanup. Recheck at
        // execution, including a live successor's adoption of the same physical display.
        if (entity.getType() == EntityType.ITEM_DISPLAY && ((!remove && !gravesConfig.isDestroyed() && entity == itemDisplay)
                || plugin.activeGraveSnapshot().stream().anyMatch(grave -> !grave.isRemove()
                        && !grave.getGravesConfig().isDestroyed() && entity == grave.getItemDisplay()))) return;
        var data = entity.getPersistentDataContainer();
        String tagged = data.get(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING);
        if (identity.equals(tagged)) { entity.remove(); return; }
        // Upgrade only the old plugin-tagged stands at the four exact native hologram positions.
        // Never adopt a successor identity or an arbitrary nearby/untagged entity.
        if (tagged != null || entity.getType() != EntityType.ARMOR_STAND
                || !Integer.valueOf(1).equals(data.get(plugin.getKey(), PersistentDataType.INTEGER))) return;
        Location actual = entity.getLocation();
        if (actual == null || actual.getWorld() != center.getWorld()
                || Math.abs(actual.getX() - center.getX()) > 0.000001
                || Math.abs(actual.getZ() - center.getZ()) > 0.000001) return;
        double y = actual.getY() - center.getY();
        if (Math.abs(y - 1.5) > 0.000001 && Math.abs(y - 1.25) > 0.000001
                && Math.abs(y - 1) > 0.000001 && Math.abs(y + 2) > 0.000001) return;
        // The matching position is in this entity's chunk. Revalidate current marker ownership
        // after scheduler admission so an old callback cannot adopt a replacement grave's stands.
        if (remove || gravesConfig.isDestroyed() || !center.getWorld().isChunkLoaded(center.getBlockX() >> 4, center.getBlockZ() >> 4)) return;
        boolean current = center.getBlock().getMetadata("Grave").stream()
                .anyMatch(value -> value.getOwningPlugin() == plugin && value.value() == this);
        if (!current) return;
        data.set(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING, identity);
        entity.remove();
    }

	/**
	 * Creates the hologram lines above the grave.
	 */
	public void createHologram() {
		if (plugin.getConfigFile().isDisableArmorStands()) {
			return;
		}
		Location hologramLocation = gravesConfig.getLocation().getBlock().getLocation().clone().add(.5, 0, .5);

		HashMap<String, String> placeholders = new HashMap<String, String>();
		placeholders.put("player", gravesConfig.getPlayerName());
		placeholders.put("time", "" + new Date(gravesConfig.getTime()));
		placeholders.put("reason", gravesConfig.getDeathMessage());

		if (topHologram != null) {
			releaseHologram(topHologram);
		}
		topHologram = newManagedHologram(hologramLocation.add(0, 1.5, 0),
				PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatGraveTop(), placeholders), true,
				false);

		if (middleHologram != null) {
			releaseHologram(middleHologram);
		}
		middleHologram = newManagedHologram(hologramLocation.subtract(0, .25, 0),
				PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatGraveMiddle(), placeholders), true,
				false);

		if (bottomHologram != null) {
			releaseHologram(bottomHologram);
		}
		bottomHologram = newManagedHologram(hologramLocation.subtract(0, .25, 0),
				PlaceholderUtils.replacePlaceHolder(plugin.getConfigFile().getFormatGraveBottom(), placeholders), true,
				false);

        for (Hologram hologram : new Hologram[] { topHologram, middleHologram, bottomHologram }) if (hologram != null)
            tagHologram(hologram, 0);
		checkGlowing();
	}

    /** AdvancedCore may defer entity construction; tag only once the actual entity exists. */
    void tagHologram(Hologram hologram, int attempt) {
        if (hologram == null || !plugin.isEnabled()) return;
        org.bukkit.entity.ArmorStand stand = hologram.getArmorStand();
        boolean retired = remove || gravesConfig.isDestroyed() || hologram != topHologram && hologram != middleHologram
                && hologram != bottomHologram && hologram != glowingHologram;
        if (retired) releaseHologram(hologram);
        if (stand != null) {
            if (!retired) stand.getPersistentDataContainer().set(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING, GraveIdentity.of(gravesConfig));
        } else if (attempt < 5) plugin.getBukkitScheduler().runTaskLater(plugin, () -> tagHologram(hologram, attempt + 1), 1L, gravesConfig.getLocation());
    }
    private void tagDisplay() {
        if (itemDisplay != null) itemDisplay.getPersistentDataContainer().set(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING, GraveIdentity.of(gravesConfig));
    }

	/**
	 * Removes the hologram for this grave.
	 */
	public void removeHologram() {
		if (topHologram != null) {
			releaseHologram(topHologram);
			topHologram = null;
		}
		if (middleHologram != null) {
			releaseHologram(middleHologram);
			middleHologram = null;
		}
		if (bottomHologram != null) {
			releaseHologram(bottomHologram);
			bottomHologram = null;
		}
		if (glowingHologram != null) {
			releaseHologram(glowingHologram);
			glowingHologram = null;
		}
	}

    /** All grave wrappers share AC's mutable registry; world work remains on the grave owner. */
    private Hologram newManagedHologram(Location location, String name, boolean marker, boolean glowing) {
        synchronized (plugin.getHologramHandler()) {
            return new Hologram(location, name, marker, glowing, plugin.getKey(), 1, "Grave", this);
        }
    }

    /** AC kill() uses a global sync handoff; unregister separately from entity-owned removal. */
    private void releaseHologram(Hologram hologram) {
        synchronized (plugin.getHologramHandler()) { plugin.getHologramHandler().remove(hologram, false); }
        org.bukkit.entity.ArmorStand stand = hologram.getArmorStand();
        if (stand != null) {
            try { plugin.getBukkitScheduler().runTask(plugin, stand::remove, stand); }
            catch (RuntimeException rejected) { plugin.debug(rejected); } // Exact PDC cleanup remains journaled.
        }
    }

	/**
	 * Checks if the grave marker is valid based on its stored display type.
	 *
	 * @return True if valid
	 */
	public boolean isValid() {
		GraveDisplayType type = getStoredDisplayTypeOrDefault();

		switch (type) {
		case CHEST:
			return gravesConfig.getLocation().getBlock().getType().equals(Material.CHEST);
		case DISPLAY_ENTITY:
			return gravesConfig.getLocation().getBlock().getType().equals(Material.BARRIER);
		case PLAYER_HEAD:
			return gravesConfig.getLocation().getBlock().getType().equals(Material.PLAYER_HEAD);
		default:
			return false;
		}
	}

	public String getGraveMessage() {
		Location loc = gravesConfig.getLocation();
		return "Location: " + loc.getWorld().getName() + " (" + loc.getBlockX() + "," + loc.getBlockY() + ","
				+ loc.getBlockZ() + ") Time of death: " + new Date(gravesConfig.getTime());
	}

	public String getNavigationMessage(Player player) {
		Location target = gravesConfig.getLocation();
		if (target == null || target.getWorld() == null) return "world unavailable";
        target = target.clone();
        Location source = player.getLocation();
        if (source != null) source = source.clone();
        if (source == null || source.getWorld() == null || !source.getWorld().getUID().equals(target.getWorld().getUID()))
            return "world " + target.getWorld().getName() + " (different world)";
		double dx = target.getX() - source.getX(), dz = target.getZ() - source.getZ();
		String vertical = target.getY() > source.getY() ? "above" : target.getY() < source.getY() ? "below" : "level";
		return target.getWorld().getName() + ", " + Math.round(Math.sqrt(dx * dx + dz * dz)) + " blocks, " + vertical;
	}

	public boolean isOwner(String player) {
		return gravesConfig.getPlayerName().equalsIgnoreCase(player);
	}

	/**
	 * Removes the grave with a reason, firing {@link GraveRemoveEvent}.
	 *
	 * @param reason Removal reason
	 */
    public void removeGrave(final GraveRemoveReason reason) {
        plugin.getBukkitScheduler().runTask(plugin, () -> removeOwned(reason), gravesConfig.getLocation());
    }
    private void removeOwned(GraveRemoveReason reason) {
        if (removeState(reason, () -> true)) cleanupMarker();
    }
    /** Player actions recheck permission and online state on the entity owner. */
    public void removeByPlayer(Player player, GraveRemoveReason reason) {
        if (player == null) return;
        plugin.getBukkitScheduler().runTask(plugin, () -> {
            if (removeState(reason, () -> player.isOnline() && plugin.isLiveGrave(this)
                    && (isOwner(player) || canAdminister(player)))) {
                plugin.getBukkitScheduler().runTask(plugin, this::cleanupMarker, gravesConfig.getLocation());
                player.sendMessage(MessageAPI.colorize("&cGrave removed"));
            }
        }, player);
    }
    private synchronized boolean removeState(GraveRemoveReason reason, java.util.function.BooleanSupplier authorized) {
        if (remove || claimInProgress || !expirationEligible(reason) || !authorized.getAsBoolean()) return false;
        GraveMarkerCleanup cleanup = plugin.getMarkerCleanup();
        GraveMarkerCleanup.Ticket ticket = cleanup == null ? null : cleanup.prepare(gravesConfig);
        if (cleanup != null && ticket == null) {
            if (reason == GraveRemoveReason.EXPIRED) retryExpiration(expiryDeadline);
            return false;
        }
        long expiringDeadline = expiryDeadline;
        GraveRemoveEvent event = new GraveRemoveEvent(this, reason == null ? GraveRemoveReason.MANUAL_REMOVE : reason);
        try { Bukkit.getPluginManager().callEvent(event); }
        catch (RuntimeException failure) { if (ticket != null) ticket.close(); throw failure; }
        if (event.isCancelled()) {
            if (ticket != null) ticket.close();
            if (reason == GraveRemoveReason.EXPIRED && expiryDeadline == expiringDeadline
                    && expiringDeadline <= System.currentTimeMillis()) expiryDeadline = 0;
            return false;
        }
        if (claimInProgress || remove || !expirationEligible(reason) || !authorized.getAsBoolean()) { if (ticket != null) ticket.close(); return false; }
        cleanupTicket = ticket;
        remove = true; gravesConfig.setDestroyed(true); gravesConfig.setDestroyedTime(System.currentTimeMillis());
        plugin.getGraveNavigation().invalidate(GraveIdentity.of(gravesConfig));
        plugin.getTrustedGraveAccessStore().revokeForGraveAsync(GraveIdentity.of(gravesConfig));
        if (limitPayoutPending) {
            limitPayoutPending = false; limitRecipient.clear();
            plugin.getGraveClaimLedger().cancelQueuedLimit(GraveIdentity.of(gravesConfig));
        }
        removeTimer(); plugin.removeGrave(this); return true;
    }
    /** Admission pressure is transient; retry one current deadline without retaining a queue of tasks. */
    private synchronized void retryExpiration(long deadline) {
        if (!plugin.isEnabled() || remove || gravesConfig.isDestroyed() || deadline <= 0 || expiryDeadline != deadline) return;
        removeTimer();
        long generation = expirationGeneration;
        expirationTask = plugin.getTimer().schedule(() -> {
            plugin.getBukkitScheduler().runTask(plugin, () -> {
                synchronized (Grave.this) {
                    if (!plugin.isEnabled() || generation != expirationGeneration || deadline != expiryDeadline
                            || remove || gravesConfig.isDestroyed()) return;
                    removeOwned(GraveRemoveReason.EXPIRED);
                }
            }, gravesConfig.getLocation());
        }, 5L, TimeUnit.SECONDS);
    }
    private boolean expirationEligible(GraveRemoveReason reason) {
        return reason != GraveRemoveReason.EXPIRED || (expiryDeadline > 0 && expiryDeadline <= System.currentTimeMillis());
    }
    public java.util.concurrent.CompletableFuture<Boolean> grantHelper(Player owner, java.util.UUID helper,
            TrustedGraveAccess.Kind kind, long expiresAt) {
        synchronized (plugin.getTrustedGraveAccessStore()) {
            if (owner == null || !owner.isOnline() || !isOwner(owner) || !owner.hasPermission("GraveStonesPlus.Trust")
                    || !plugin.isLiveGrave(this)) return java.util.concurrent.CompletableFuture.completedFuture(false);
            return plugin.getTrustedGraveAccessStore().grantAsync(GraveIdentity.of(gravesConfig), helper, kind, expiresAt);
        }
    }

    public java.util.concurrent.CompletableFuture<Boolean> revokeHelper(Player owner, java.util.UUID helper,
            TrustedGraveAccess.Kind kind) {
        synchronized (plugin.getTrustedGraveAccessStore()) {
            if (owner == null || !owner.isOnline() || !isOwner(owner) || !owner.hasPermission("GraveStonesPlus.Trust.Revoke")
                    || !plugin.isLiveGrave(this)) return java.util.concurrent.CompletableFuture.completedFuture(false);
            return plugin.getTrustedGraveAccessStore().revokeAsync(GraveIdentity.of(gravesConfig), helper, kind);
        }
    }

	/**
	 * Removes the grave using {@link GraveRemoveReason#MANUAL_REMOVE}.
	 */
	@Deprecated
	public void removeGrave() {
		removeGrave(GraveRemoveReason.MANUAL_REMOVE);
	}

	/**
	 * Schedule the grave expiration task using the shared scheduler.
	 *
	 * @param duration the configured grave lifetime
	 */
	private void schedule(ParsedDuration duration) {
		if (duration == null) {
			return;
		}

		if (expirationTask != null && !expirationTask.isDone()) {
			expirationTask.cancel(false);
		}

		final long durationMillis = duration.getMillis();
		long deathTime = gravesConfig.getTime();
		long expireTime = GraveDeadlines.deadline(deathTime, durationMillis);
        expiryDeadline = expireTime;
        if (expireTime <= 0) return;
		long delay = expireTime - System.currentTimeMillis();

		if (delay <= 0) {
			removeGrave(GraveRemoveReason.EXPIRED);
			return;
		}

		expirationTask = plugin.getTimer().schedule(new Runnable() {

			@Override
			public void run() {
				long currentExpireTime = expiryDeadline;
				if (currentExpireTime > 0 && currentExpireTime <= System.currentTimeMillis()) {
					removeGrave(GraveRemoveReason.EXPIRED);
				}
			}
		}, delay + 500L, TimeUnit.MILLISECONDS);
	}

	/**
	 * Legacy support for minute-based grave time limits.
	 *
	 * @param timeLimit the time limit in minutes
	 */
	public void checkTimeLimit(int timeLimit) {
		if (timeLimit <= 0) {
            removeTimer(); expiryDeadline = 0;
			return;
		}

		checkTimeLimit(ParsedDuration.parse(timeLimit + "", TimeUnit.MINUTES));
	}

	/**
	 * Checks the configured grave time limit and removes or schedules the grave.
	 *
	 * @param timeLimitRaw the configured time limit value, supporting either legacy
	 *                     minute values such as {@code 30} or duration values such
	 *                     as {@code 30m}, {@code 2h}, or {@code 7d}
	 */
	public void checkTimeLimit(String timeLimitRaw) {
		removeTimer(); expiryDeadline = 0;
        if (timeLimitRaw == null || timeLimitRaw.trim().isEmpty()) {
			return;
		}

		String trimmed = timeLimitRaw.trim();

		try {
			if (trimmed.matches("\\d+")) {
				checkTimeLimit(Integer.parseInt(trimmed));
				return;
			}

			ParsedDuration duration = ParsedDuration.parse(trimmed, TimeUnit.MINUTES);
			checkTimeLimit(duration);
		} catch (NumberFormatException e) {
			plugin.debug("Invalid numeric grave time limit: " + timeLimitRaw);
		} catch (Exception e) {
			plugin.debug("Invalid grave time limit duration: " + timeLimitRaw);
		}
	}

	/**
	 * Checks the configured grave time limit using a parsed duration and removes or
	 * schedules the grave.
	 *
	 * @param timeLimit the parsed duration
	 */
	public void checkTimeLimit(ParsedDuration timeLimit) {
		if (timeLimit == null) {
            removeTimer(); expiryDeadline = 0;
			return;
		}

		long durationMillis = timeLimit.getMillis();
		if (durationMillis <= 0) {
            removeTimer(); expiryDeadline = 0;
			return;
		}

		long deathTime = gravesConfig.getTime();
		long expireTime = GraveDeadlines.deadline(deathTime, durationMillis);
        expiryDeadline = expireTime;

		if (expireTime > 0 && expireTime <= System.currentTimeMillis()) {
			removeGrave(GraveRemoveReason.EXPIRED);
		} else {
			schedule(timeLimit);
		}
	}

    public long getExpiryDeadline() { return expiryDeadline; }

    /** Render on the viewer's entity owner; navigation lore snapshots their current position. */
    public BInventoryButton getNavigationGUIItem(Player viewer) { return guiItem(true, viewer); }
    public BInventoryButton getGUIItem() { return guiItem(false, null); }
    private BInventoryButton guiItem(boolean navigation, Player menuViewer) {
		Location loc = gravesConfig.getLocation();
		BInventoryButton b = new BInventoryButton(
				new ItemBuilder(Material.PLAYER_HEAD).setSkullOwner(Bukkit.getOfflinePlayer(gravesConfig.getUuid()))
						.setName("&3&l" + gravesConfig.getPlayerName())
						.addLoreLine("&3" + "Location: " + loc.getWorld().getName() + " (" + loc.getBlockX() + ","
								+ loc.getBlockY() + "," + loc.getBlockZ() + ")")
						.addLoreLine("&3" + "Time of death: " + new Date(gravesConfig.getTime()))
						.addLoreLine(navigation ? "&bClick to select compass; right click to recover nearby" : "&bClick to Teleport").addLoreLine("&4Shift right click to remove")
						.addLoreLine("&cShift left click to view items")
                        .addLoreLine(navigation ? "&7" + getNavigationMessage(menuViewer) : "")) {

            @Override
            public void onClick(ClickEvent clickEvent) {
                Grave grave = (Grave) getData("grave");
                Player viewer = clickEvent.getPlayer();
                ClickType click = clickEvent.getClick();
                // BInventory clicks are asynchronous by default. Only immutable
                // click intent crosses this hop; authorization and player reads wait.
                plugin.getBukkitScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled() || !viewer.isOnline()) return;
                    if (!plugin.isLiveGrave(grave) || (!plugin.canTrack(grave, viewer) && !canAdminister(viewer))) {
                        viewer.sendMessage(MessageAPI.colorize("&cThat grave is no longer accessible.")); return;
                    }
                    if (click == ClickType.SHIFT_RIGHT) {
                        if (!grave.isOwner(viewer) && !canAdminister(viewer)) {
                            viewer.sendMessage(MessageAPI.colorize("&cOnly the grave owner can remove this grave.")); return;
                        }
                        grave.removeByPlayer(viewer, GraveRemoveReason.MANUAL_REMOVE);
                    } else if (click == ClickType.SHIFT_LEFT) {
                        if (!grave.canViewContents(viewer)) {
                            viewer.sendMessage(MessageAPI.colorize("&cYou can track this grave but cannot recover its contents.")); return;
                        }
                        grave.openGUIWithItems(viewer);
                    } else if (navigation) {
                        if (click == ClickType.RIGHT) {
                            if (!viewer.hasPermission("GraveStonesPlus.ClaimGrave")) {
                                viewer.sendMessage(MessageAPI.colorize("&cYou don't have permission to claim graves.")); return;
                            }
                            double distance = grave.getDistance(viewer);
                            if (distance >= 0 && distance < plugin.getConfigFile().getGraveClaimDistance()) grave.claimNearby(viewer);
                            else viewer.sendMessage("\u00a7cMove near the grave to recover it.");
                        } else {
                            if (!viewer.hasPermission("GraveStonesPlus.Compass")) {
                                viewer.sendMessage(MessageAPI.colorize("&cYou don't have permission to use compasses.")); return;
                            }
                            grave.giveCompass(viewer);
                        }
                    } else if (grave.isOwner(viewer) || canAdminister(viewer)) {
                        Location destination = grave.getGravesConfig().getLocation().clone().add(0, 1, 0);
                        if (plugin.getBukkitScheduler().getFoliaLib().isFolia())
                            plugin.getBukkitScheduler().getFoliaLib().getImpl().teleportAsync(viewer, destination);
                        else viewer.teleport(destination);
                    }
                }, viewer);
            }
		};
		b.addData("grave", this);
		return b;
	}

	public BInventoryButton getGUIItemBroken() {
		Location loc = gravesConfig.getLocation();
		BInventoryButton b = new BInventoryButton(
				new ItemBuilder(Material.PLAYER_HEAD).setSkullOwner(Bukkit.getOfflinePlayer(gravesConfig.getUuid()))
						.setName("&3&l" + gravesConfig.getPlayerName())
						.addLoreLine("&3" + "Location: " + loc.getWorld().getName() + " (" + loc.getBlockX() + ","
								+ loc.getBlockY() + "," + loc.getBlockZ() + ")")
						.addLoreLine("&3" + "Time of death: " + new Date(gravesConfig.getTime()))
						.addLoreLine("&b" + "Click to Teleport").addLoreLine("&4Shift right click to create")
						.addLoreLine("&cShift left click to view items")
						.addLoreLine("&aTime of removal: " + new Date(gravesConfig.getDestroyedTime()))) {

			@Override
			public void onClick(ClickEvent clickEvent) {
				Grave grave = (Grave) getData("grave");
                Player actor = clickEvent.getPlayer();
                ClickType intent = clickEvent.getClick();
                if (actor == null) return;
                // Native BInventory dispatch is asynchronous; only click intent crosses this hop.
                plugin.getBukkitScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled() || !actor.isOnline() || !grave.canAdministerHistorical(actor)) return;
                    if (intent == ClickType.SHIFT_RIGHT) {
                        plugin.recreateBrokenGrave(grave, actor).whenComplete((restored, failure) ->
                            plugin.getBukkitScheduler().runTask(plugin, () -> {
                                if (plugin.isEnabled() && actor.isOnline()) actor.sendMessage(Boolean.TRUE.equals(restored) && failure == null
                                        ? MessageAPI.colorize("&cGrave readded")
                                        : "\u00a7cThis grave could not be recreated; recovery records or unavailable storage may block it.");
                            }, actor));
                    } else if (intent == ClickType.SHIFT_LEFT) {
                        grave.openGUIWithItems(actor);
                    } else {
                        grave.teleportHistorical(actor);
                    }
                }, actor);

			}
		};
		b.addData("grave", this);
		return b;
	}

	public String getAllGraveMessage() {
		Location loc = gravesConfig.getLocation();
		return "Player: " + gravesConfig.getPlayerName() + ", Location: " + loc.getWorld().getName() + " ("
				+ loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + ") Time of death: "
				+ new Date(gravesConfig.getTime());
	}

    /** Caller owns the player; one location snapshot keeps world and distance consistent. */
    public double getDistance(Player p) {
        Location target = gravesConfig.getLocation();
        if (p == null || target == null || target.getWorld() == null) return -1;
        target = target.clone();
        Location source = p.getLocation();
        if (source == null || source.getWorld() == null) return -1;
        source = source.clone();
        if (!source.getWorld().getUID().equals(target.getWorld().getUID())) return -1;
        return target.distance(source);
    }

    public boolean isGrave(Block clicked) {
        Location loc = gravesConfig.getLocation();
        return clicked != null && loc != null && loc.getWorld() != null
                && loc.getWorld().getUID().equals(clicked.getWorld().getUID())
                && loc.getBlockX() == clicked.getX() && loc.getBlockY() == clicked.getY() && loc.getBlockZ() == clicked.getZ();
    }

	/**
	 * For display entities, try to find the ItemDisplay and recreate it if missing.
	 * This prevents startup from marking graves as broken just because the entity
	 * is not discoverable on the first tick.
	 */
	public void checkBlockDisplayAndFixIfMissing() {
		if (getStoredDisplayTypeOrDefault() != GraveDisplayType.DISPLAY_ENTITY) {
			return;
		}

		if (!gravesConfig.getLocation().getBlock().getType().equals(Material.BARRIER)) {
			return;
		}

		if (gravesConfig.getDisplayUUID() != null) {
			Entity e = Bukkit.getEntity(gravesConfig.getDisplayUUID());
			if (e instanceof ItemDisplay) {
				itemDisplay = (ItemDisplay) e;
				if (itemDisplay.isDead()) {
					itemDisplay = null;
				}
			}
		}

		if (itemDisplay == null) {
			try {
				itemDisplay = plugin.getGraveDisplayEntityHandler().getItemDisplay(this);
				if (itemDisplay != null && itemDisplay.isDead()) {
					itemDisplay = null;
				}
			} catch (Exception ignored) {
				// best-effort
			}
		}

		if (itemDisplay == null) {
			try {
				itemDisplay = plugin.getGraveDisplayEntityHandler().createDisplay(this);
				if (itemDisplay != null) {
					gravesConfig.setDisplayUUID(itemDisplay.getUniqueId());
					itemDisplay.getPersistentDataContainer().set(plugin.getKey(), PersistentDataType.INTEGER, 1);
                        itemDisplay.getPersistentDataContainer().set(GraveMarkerCleanup.identityKey(plugin), PersistentDataType.STRING, GraveIdentity.of(gravesConfig));
				}
			} catch (Exception e) {
				plugin.debug(e);
			}
		}
        tagDisplay();
	}

    public void giveCompass(Player player) {
        plugin.getBukkitScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled() || !player.isOnline() || !player.hasPermission("GraveStonesPlus.Compass")
                    || !plugin.getGraveNavigation().select(player, this)) return;
            ItemStack compass = new ItemStack(Material.COMPASS);
            CompassMeta meta = (CompassMeta) compass.getItemMeta();
            meta.setLodestone(gravesConfig.getLocation()); meta.setLodestoneTracked(false);
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "gravecompass"), PersistentDataType.LONG, gravesConfig.getTime());
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "graveidentity"), PersistentDataType.STRING, GraveIdentity.of(gravesConfig));
            meta.setDisplayName(gravesConfig.getPlayerName() + " grave"); meta.setLore(java.util.List.of(getNavigationMessage(player)));
            compass.setItemMeta(meta);
            // AC giveItem queues another unguarded delivery, including retained overflow.
            // Issue this optional navigation item at the authorization boundary instead.
            var overflow = player.getInventory().addItem(compass);
            if (!overflow.isEmpty()) {
                if (plugin.getOptions().isDropOnFullInv()) {
                    for (ItemStack item : overflow.values()) player.getWorld().dropItem(player.getLocation(), item);
                } else {
                    player.sendMessage("\u00a7cMake room in your inventory, then request the grave compass again.");
                    return;
                }
            }
            player.sendMessage("\u00a7aSelected grave: " + getNavigationMessage(player));
        }, player);
    }

	public synchronized void removeTimer() {
        expirationGeneration++;
		if (expirationTask != null && !expirationTask.isDone()) {
			expirationTask.cancel(false);
		}
	}

	public boolean canPlayerBreak(Player player) {
		if (isOwner(player)) {
			return true;
		}
		return plugin.canRecover(this, player) || player.hasPermission("GraveStonesPlus.BreakOtherGraves");
	}

	public boolean canNonOwnerClaim() {
		long claimDelay = plugin.getConfigFile().getBreakOtherGravesTimeBeforeBreakable().getMillis();

		if (claimDelay <= 0) {
			return true;
		}
		long graveTime = getGravesConfig().getTime();

		return System.currentTimeMillis() >= graveTime + claimDelay;
	}

	public String getTimeUntilNonOwnerClaimFormatted() {
		long claimDelay = plugin.getConfigFile().getBreakOtherGravesTimeBeforeBreakable().getMillis();

		if (claimDelay <= 0) {
			return "Ready";
		}

		long graveTime = getGravesConfig().getTime();
		long remaining = (graveTime + claimDelay) - System.currentTimeMillis();

		if (remaining <= 0) {
			return "Ready";
		}

		return formatDuration(remaining);
	}

	private String formatDuration(long millis) {
		long seconds = millis / 1000;

		long days = seconds / 86400;
		seconds %= 86400;

		long hours = seconds / 3600;
		seconds %= 3600;

		long minutes = seconds / 60;
		seconds %= 60;

		StringBuilder sb = new StringBuilder();

		if (days > 0)
			sb.append(days).append("d ");
		if (hours > 0)
			sb.append(hours).append("h ");
		if (minutes > 0)
			sb.append(minutes).append("m ");
		if (seconds > 0 || sb.length() == 0)
			sb.append(seconds).append("s");

		return sb.toString().trim();
	}

	public void checkGlowing() {
		if (!plugin.getConfigFile().isGlowingEffectNearGrave() || plugin.getConfigFile().isDisableArmorStands()) {
			removeGlowingHologram();
			return;
		}

		if (gravesConfig.getUuid() == null || gravesConfig.getLocation() == null
				|| gravesConfig.getLocation().getWorld() == null) {
			removeGlowingHologram();
			return;
		}

		try {
			Player player = Bukkit.getPlayer(gravesConfig.getUuid());
			if (player == null || !player.isOnline()) {
				removeGlowingHologram();
				return;
			}

			Location graveLoc = gravesConfig.getLocation();
			if (!player.getWorld().getUID().equals(graveLoc.getWorld().getUID())) {
				removeGlowingHologram();
				return;
			}

			if (player.getLocation().distance(graveLoc) > plugin.getConfigFile().getGlowingEffectDistance()) {
				removeGlowingHologram();
				return;
			}

			Location glowLoc = graveLoc.getBlock().getLocation().clone().add(0.5, -2, 0.5);

			if (glowingHologram == null) {
				glowingHologram = newManagedHologram(glowLoc, "", false, true);
                tagHologram(glowingHologram, 0);
			}

			glowingHologram.glow(true);
		} catch (Exception e) {
			plugin.debug(e);
		}
	}

	private void removeGlowingHologram() {
		if (glowingHologram != null) {
			releaseHologram(glowingHologram);
			glowingHologram = null;
		}
	}

	public void markRecreated() { removeTimer(); expiryDeadline = 0; remove = false; }
    /**
     * Legacy fire-and-forget entry point. Route it through the plugin wrapper so
     * the same list and storage transition used by administrative recovery is
     * applied after the marker has been recreated. The historical API
     * intentionally creates a marker.
     */
    public void createBrokenGrave() { plugin.recreateBrokenGrave(this, null, true); }
    /** Check durable history before any marker, destroyed-state or active-list mutation. */
    public java.util.concurrent.CompletableFuture<Boolean> recreateAsync(Player actor, boolean createMarker) {
        final String identity = GraveIdentity.of(gravesConfig);
        synchronized (this) {
            if (!recreationStateAllowed(identity)) return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        var result = new java.util.concurrent.CompletableFuture<Boolean>();
        plugin.getGraveClaimLedger().canRecreate(identity).thenCompose(allowed -> {
            // Removal can become historical even when its first revocation failed or
            // could not enter the worker. Reconcile those persisted grants before
            // exposing this identity as live again, including after restart.
            synchronized (this) {
                if (!Boolean.TRUE.equals(allowed) || !recreationStateAllowed(identity))
                    return java.util.concurrent.CompletableFuture.completedFuture(false);
                return plugin.getTrustedGraveAccessStore().revokeForGraveAsync(identity);
            }
        }).whenComplete((allowed, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(allowed)) { result.complete(false); return; }
            try {
                plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtLocation(gravesConfig.getLocation(), task -> {
                    synchronized (this) {
                        if (!recreationStateAllowed(identity)) { result.complete(false); return; }
                    }
                    Runnable commit = () -> {
                        synchronized (this) {
                            if (!recreationStateAllowed(identity) || actor != null && (!actor.isOnline() || !canAdministerHistorical(actor))) { result.complete(false); return; }
                            markRecreated(); gravesConfig.setDestroyed(false); gravesConfig.setDestroyedTime(0);
                            if (createMarker) {
                                createGrave();
                                plugin.getBukkitScheduler().runTask(plugin, () -> { if (!remove && plugin.isEnabled()) createHologram(); }, gravesConfig.getLocation());
                            }
                            result.complete(true);
                        }
                    };
                    if (actor == null) commit.run();
                    else try { plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtEntityWithFallback(actor, ignored -> commit.run(), () -> result.complete(false))
                            .whenComplete((scheduled, error) -> { if (error != null || scheduled != com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS) result.complete(false); });
                    } catch (RuntimeException rejected) { result.completeExceptionally(rejected); }
                }).whenComplete((unused, error) -> { if (error != null) result.completeExceptionally(error); });
            } catch (RuntimeException rejected) { result.completeExceptionally(rejected); }
        });
        return result;
    }

    private boolean recreationStateAllowed(String identity) {
        return identity != null && plugin.isEnabled() && gravesConfig.isDestroyed() && !claimInProgress
                && identity.equals(GraveIdentity.of(gravesConfig)) && !plugin.getGraveClaimLedger().isBlocked(identity);
    }
    public void teleportHistorical(Player actor) {
        if (actor == null) return;
        Location target = gravesConfig.getLocation().clone().add(0, 1, 0);
        plugin.getBukkitScheduler().runTask(plugin, () -> {
            if (actor.isOnline() && plugin.isEnabled() && canAdministerHistorical(actor)) plugin.getBukkitScheduler().getFoliaLib().getImpl().teleportAsync(actor, target);
        }, actor);
    }

    /**
     * Legacy item-only drop API. This does not remove the grave, pay experience,
     * or reserve a recovery; callers remain responsible for the grave lifecycle.
     * Items are captured before the region handoff so later edits cannot change
     * an already requested drop. Player is retained for source compatibility.
     */
    public void dropItemsOnGround(Player recipient) {
        Location location = gravesConfig.getLocation();
        if (location == null || location.getWorld() == null) return;
        Location target = location.clone();
        var items = GravePayout.select(gravesConfig.getItems(), plugin.getConfigFile().getPercentageDrops(),
                () -> ThreadLocalRandom.current().nextInt(100));
        plugin.getBukkitScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled()) return;
            for (ItemStack item : items.values()) target.getWorld().dropItem(target, item);
        }, target);
    }

    /** Limit-removal payout shares the claim ledger, so queued drops cannot race recovery. */
    public void dropAndRemoveForLimit(Player recipient) {
        String identity = GraveIdentity.of(gravesConfig);
        synchronized (this) {
            if (remove || claimInProgress || limitPayoutPending || !plugin.isLiveGrave(this) || plugin.getGraveClaimLedger().isBlocked(identity)) return;
            claimInProgress = true;
            limitRecipient = new java.lang.ref.WeakReference<>(recipient);
        }
        final String evidence;
        try { evidence = GraveClaimLedger.evidence(com.bencodez.gravestonesplus.storage.GraveStorageManager.snapshot(gravesConfig)); }
        catch (RuntimeException invalid) { releaseClaim(); return; }
        plugin.getGraveClaimLedger().queueLimit(identity, gravesConfig.getUuid(), evidence).whenComplete((saved, failure) -> {
            if (failure == null && Boolean.TRUE.equals(saved)) {
                limitPayoutPending = true;
                plugin.getGraveRestoration().retainLimit(this);
            }
            releaseClaim();
            if (limitPayoutPending) plugin.getGraveRestoration().retry();
        });
    }
    void markLimitPayoutPending() { limitPayoutPending = true; }
    /** Invoked by restoration only on a loaded chunk's owning scheduler. */
    void resumeLimitPayout() {
        String identity = GraveIdentity.of(gravesConfig);
        synchronized (this) {
            if (remove || claimInProgress || !limitPayoutPending || !plugin.isEnabled()) return;
            if (expiryDeadline > 0 && expiryDeadline <= System.currentTimeMillis()) {
                removeOwned(GraveRemoveReason.EXPIRED); return;
            }
            if (!plugin.isLiveGrave(this)) return;
            if (!prepareCleanup()) return;
            claimInProgress = true;
        }
        final com.bencodez.gravestonesplus.storage.GravesConfig captured;
        try { captured = com.bencodez.gravestonesplus.storage.GraveStorageManager.snapshot(gravesConfig); }
        catch (RuntimeException invalid) { releaseClaim(); return; }
        plugin.getGraveClaimLedger().reserveQueuedLimit(identity, gravesConfig.getUuid()).whenComplete((saved, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(saved)) { releaseClaim(); return; }
            java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable retired = () -> { if (started.compareAndSet(false, true)) deferLimitPayout(identity); };
            try {
                plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtLocation(captured.getLocation(), task -> {
                    if (!started.compareAndSet(false, true)) return;
                    Location loc = captured.getLocation();
                    // The disk reservation can outlive the grave's remaining lifetime.
                    // No world payout has occurred, so return ownership to the queued
                    // record before releaseClaim dispatches normal cancellable expiry.
                    if (expiryDeadline > 0 && expiryDeadline <= System.currentTimeMillis()) { deferLimitPayout(identity); return; }
                    if (!plugin.isEnabled() || !plugin.isLiveGrave(this) || loc.getWorld() == null
                            || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) { deferLimitPayout(identity); return; }
                    GraveRemoveEvent event = new GraveRemoveEvent(this, GraveRemoveReason.LIMIT_REACHED);
                    Bukkit.getPluginManager().callEvent(event);
                    if (event.isCancelled()) {
                        limitPayoutPending = false; cancelClaim(identity); return;
                    }
                    // Listeners may run until the deadline or change the time limit.
                    if (expiryDeadline > 0 && expiryDeadline <= System.currentTimeMillis()) { deferLimitPayout(identity); return; }
                    if (!plugin.isLiveGrave(this)) {
                        limitPayoutPending = false; cancelClaim(identity); return;
                    }
                    if (!plugin.isEnabled() || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                        deferLimitPayout(identity); return;
                    }
                    try {
                        for (ItemStack item : GravePayout.select(captured.getItems(), plugin.getConfigFile().getPercentageDrops(),
                                () -> ThreadLocalRandom.current().nextInt(100)).values()) loc.getWorld().dropItem(loc, item);
                        if (captured.getExp() > 0) loc.getWorld().spawn(loc, org.bukkit.entity.ExperienceOrb.class).setExperience(captured.getExp());
                        plugin.getGraveClaimLedger().paid(identity, gravesConfig.getUuid()).whenComplete((paid, paidFailure) -> {
                            if (paidFailure != null || !Boolean.TRUE.equals(paid)) plugin.getLogger().severe("Grave ground payout completed but its completion record could not be saved; reservation prevents replay.");
                        });
                        remove = true; gravesConfig.setDestroyed(true); gravesConfig.setDestroyedTime(System.currentTimeMillis());
                        plugin.removeGrave(this); plugin.getTrustedGraveAccessStore().revokeForGraveAsync(identity);
                        plugin.getGraveNavigation().invalidate(identity); removeTimer(); cleanupMarker();
                        Player recipient = limitRecipient.get(); limitRecipient.clear();
                        if (recipient != null) plugin.getBukkitScheduler().runTask(plugin, () -> {
                            if (plugin.isEnabled() && recipient.isOnline()) recipient.sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatGraveLimitBreak()));
                        }, recipient);
                    } catch (RuntimeException interrupted) {
                        plugin.getLogger().severe("Grave ground payout interrupted after reservation; replay is blocked and exact contents/XP remain in recovery evidence.");
                    } finally { releaseClaim(); }
                }).whenComplete((result, schedulingFailure) -> { if (schedulingFailure != null) retired.run(); });
            } catch (RuntimeException schedulingFailure) { retired.run(); }
        });
    }
    private void deferLimitPayout(String identity) {
        plugin.getGraveClaimLedger().deferLimitReservation(identity, gravesConfig.getUuid())
                .whenComplete((saved, failure) -> releaseClaim());
    }

	public boolean canAdminister(Player player) {
        return player.hasPermission("GraveStonesPlus.Admin") || player.hasPermission("GraveStonesPlus.AllGraves")
                || player.hasPermission("GraveStonesPlus.AllGraves.Player");
    }
    private boolean canAdministerHistorical(Player player) {
        return player != null && (player.hasPermission("GraveStonesPlus.Admin")
                || player.hasPermission("GraveStonesPlus.AllBrokenGraves")
                || player.hasPermission("GraveStonesPlus.AllBrokenGraves.Player"));
    }
    public boolean canViewContents(Player player) {
        return player != null && (isOwner(player) || plugin.canRecover(this, player)
                || canAdminister(player) || gravesConfig.isDestroyed() && canAdministerHistorical(player)
                || player.hasPermission("GraveStonesPlus.ViewGrave"));
    }
    public void openGUIWithItems(Player player) {
        if (player == null) return;
        plugin.getBukkitScheduler().runTask(plugin, () -> {
            if (player.isOnline() && canViewContents(player)) openItemsOwned(player);
        }, player);
    }
    private void openItemsOwned(Player p) {
		BInventory inv = new BInventory("Grave Items");
		for (Entry<Integer, ItemStack> entry : getGravesConfig().getItems().entrySet()) {
			switch (entry.getKey().intValue()) {
			case -1:
				inv.addButton(36, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// view only
					}
				});
				break;
			case -2:
				inv.addButton(37, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// view only
					}
				});
				break;
			case -3:
				inv.addButton(38, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// view only
					}
				});
				break;
			case -4:
				inv.addButton(39, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// view only
					}
				});
				break;
			case -5:
				inv.addButton(44, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// view only
					}
				});
				break;
			default:
				int num = entry.getKey().intValue();
				if (num < 9) {
					num = 27 + num;
				} else {
					num = num - 9;
				}

				inv.addButton(num, new BInventoryButton(entry.getValue()) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// view only
					}
				});
				break;
			}
		}

		inv.addButton(53,
				new BInventoryButton(new ItemBuilder("OAK_SIGN").setName(getGravesConfig().getPlayerName())
						.addLoreLine("&cDeath: " + getGravesConfig().getDeathMessage())
						.addLoreLine("&cTime: " + getGravesConfig().getTime())
						.addLoreLine("&cEXP: " + getGravesConfig().getExp())) {

					@Override
					public void onClick(ClickEvent clickEvent) {
						// info only
					}
				});

		inv.openInventory(p);
	}

    public void claim(final Player player) {
        if (player == null) return;
        plugin.getBukkitScheduler().runTask(plugin, () -> beginClaim(player, null, false), player);
    }
    /** Menu/command recovery retains its permission and nearby requirement through payout. */
    public void claimNearby(final Player player) {
        if (player != null) plugin.getBukkitScheduler().runTask(plugin, () -> beginClaim(player, null, true), player);
    }
    private boolean nearbyClaimAllowed(Player player) {
        if (!player.hasPermission("GraveStonesPlus.ClaimGrave")) return false;
        double distance = getDistance(player);
        return distance >= 0 && distance < plugin.getConfigFile().getGraveClaimDistance();
    }
    public void claimAfterTimedBreak(Player player, com.bencodez.gravestonesplus.breaking.PlayerBreakManager.CompletedBreak proof) {
        if (player != null && proof != null) plugin.getBukkitScheduler().runTask(plugin, () -> beginClaim(player, proof, false), player);
    }
    private boolean claimAllowed(Player player, com.bencodez.gravestonesplus.breaking.PlayerBreakManager.CompletedBreak proof) {
        return plugin.canRecover(this, player) || legacyClaimAllowed(player) || (proof != null && proof.allows(player, this));
    }
    private void beginClaim(Player player, com.bencodez.gravestonesplus.breaking.PlayerBreakManager.CompletedBreak proof, boolean nearby) {
        final String identity = GraveIdentity.of(gravesConfig);
        synchronized (this) {
            if (claimInProgress || !player.isOnline() || !plugin.isLiveGrave(this)
                    || (!claimAllowed(player, proof) || (nearby && !nearbyClaimAllowed(player)))) return;
            if (plugin.getGraveClaimLedger().isBlocked(identity)) {
                player.sendMessage("\u00a7cThis grave has a pending or completed recovery record; it cannot be paid again."); return;
            }
            if (!prepareCleanup()) return;
            claimInProgress = true;
        }
        // Events are part of the admission boundary: handlers may amend the live
        // grave contents or XP, so capture only after both callbacks have run.
        GraveClaimEvent claimEvent = new GraveClaimEvent(this, player, isOwner(player));
        GraveRemoveEvent removeEvent = new GraveRemoveEvent(this, GraveRemoveReason.CLAIMED);
        try {
            Bukkit.getPluginManager().callEvent(claimEvent);
            if (!claimEvent.isCancelled()) Bukkit.getPluginManager().callEvent(removeEvent);
        } catch (RuntimeException callbackFailure) {
            releaseClaim();
            plugin.getLogger().warning("Grave claim callback failed; claim remains available without payout.");
            return;
        }
        if (claimEvent.isCancelled() || removeEvent.isCancelled() || !player.isOnline()
                || !plugin.isEnabled() || !plugin.isLiveGrave(this) || (!claimAllowed(player, proof) || (nearby && !nearbyClaimAllowed(player)))) {
            releaseClaim(); return;
        }
        final com.bencodez.gravestonesplus.storage.GravesConfig captured;
        final String evidence;
        try {
            captured = com.bencodez.gravestonesplus.storage.GraveStorageManager.snapshot(gravesConfig);
            evidence = GraveClaimLedger.evidence(captured);
        } catch (RuntimeException invalid) {
            releaseClaim();
            player.sendMessage("\u00a7cGrave recovery evidence could not be captured. No payout was made."); return;
        }
        plugin.getGraveClaimLedger().reserve(identity, player.getUniqueId(), evidence).whenComplete((saved, failure) -> {
            java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
            Runnable retired = () -> {
                if (started.compareAndSet(false, true)) {
                    if (failure == null && Boolean.TRUE.equals(saved)) cancelClaim(identity); else releaseClaim();
                }
            };
            try {
            plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtEntityWithFallback(player, task -> {
                if (!started.compareAndSet(false, true)) return;
                if (failure != null || !Boolean.TRUE.equals(saved)) {
                    releaseClaim();
                    player.sendMessage("\u00a7cRecovery was not durably reserved. No payout was made; restart/reconcile storage before retrying."); return;
                }
                // Serialize the helper authorization boundary with revocation admission.
                synchronized (plugin.getTrustedGraveAccessStore()) {
                    if (!player.isOnline() || !plugin.isEnabled() || !plugin.isLiveGrave(this)
                            || (!claimAllowed(player, proof) || (nearby && !nearbyClaimAllowed(player)))) { cancelClaim(identity); return; }
                    try {
                        java.util.Map<Integer, ItemStack> payout = GravePayout.select(captured.getItems(), plugin.getConfigFile().getPercentageDrops(), () -> ThreadLocalRandom.current().nextInt(100));
                        List<java.util.concurrent.CompletableFuture<Void>> overflow = new ArrayList<>();
                        boolean displaced = GravePayout.deliver(player, payout, captured.getExp(), item -> {
                            var handler = plugin.getFullInventoryHandler();
                            if (handler != null) overflow.add(handler.giveItemAsync(player, item).toCompletableFuture());
                            else player.getWorld().dropItem(player.getLocation(), item);
                        });
                        if (overflow.isEmpty()) completeClaim(player, identity, displaced);
                        else java.util.concurrent.CompletableFuture.allOf(overflow.toArray(java.util.concurrent.CompletableFuture[]::new))
                                .whenComplete((unused, deliveryFailure) -> finishOverflowClaim(player, identity, displaced, deliveryFailure));
                    } catch (RuntimeException payoutFailure) {
                        plugin.getLogger().severe("Grave recovery interrupted after durable reservation; automatic replay is blocked. Inspect selected-claims.yml and retained grave data.");
                        player.sendMessage("\u00a7cRecovery was interrupted. This grave is held for administrator reconciliation; do not retry the payout.");
                        releaseClaim();
                    }
                }
            }, retired).whenComplete((result, schedulingFailure) -> {
                if (schedulingFailure != null || result != com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS) retired.run();
            });
            } catch (RuntimeException schedulingFailure) { retired.run(); }
        });
    }
    private void finishOverflowClaim(Player player, String identity, boolean displaced, Throwable deliveryFailure) {
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable retired = () -> {
            if (!started.compareAndSet(false, true)) return;
            plugin.getLogger().warning("Grave overflow completion could not be acknowledged on its recipient; recovery evidence remains reserved.");
            releaseClaim();
        };
        try {
            plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtEntityWithFallback(player, task -> {
                if (!started.compareAndSet(false, true)) return;
                if (deliveryFailure == null && player.isOnline() && plugin.isEnabled()) completeClaim(player, identity, displaced);
                else {
                    plugin.getLogger().warning("Grave overflow delivery was not acknowledged; recovery evidence remains reserved for reconciliation.");
                    if (player.isOnline()) player.sendMessage("\u00a7cRecovery is held for administrator reconciliation; overflow delivery was not acknowledged.");
                    releaseClaim();
                }
            }, retired).whenComplete((scheduled, failure) -> {
                if (failure != null || scheduled != com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS) retired.run();
            });
        } catch (RuntimeException schedulingFailure) { retired.run(); }
    }
    /** Recipient-owner commit boundary, reached only after all selected items and XP are acknowledged. */
    private void completeClaim(Player player, String identity, boolean displaced) {
        try {
            plugin.getGraveClaimLedger().paid(identity, player.getUniqueId()).whenComplete((paidSaved, paidFailure) -> {
                if (paidFailure != null || !Boolean.TRUE.equals(paidSaved)) plugin.getLogger().severe("Grave payout completed but completion record could not be saved; durable reservation still prevents replay.");
            });
            remove = true; gravesConfig.setDestroyed(true); gravesConfig.setDestroyedTime(System.currentTimeMillis());
            plugin.removeGrave(this);
            plugin.getTrustedGraveAccessStore().revokeForGraveAsync(identity);
            plugin.getGraveNavigation().invalidate(identity); removeTimer();
            plugin.getBukkitScheduler().runTask(plugin, this::cleanupMarker, gravesConfig.getLocation());
            if (isOwner(player) || plugin.getConfigFile().isBreakOtherGravesSendMessage()) player.sendMessage(MessageAPI.colorize(isOwner(player)
                    ? plugin.getConfigFile().getFormatGraveBroke()
                    : MessageAPI.replacePlaceHolder(plugin.getConfigFile().getFormatGraveBrokeOther(), "player", gravesConfig.getPlayerName())));
            if (displaced) player.sendMessage(MessageAPI.colorize(plugin.getConfigFile().getFormatItemsNotInGrave()));
        } finally { releaseClaim(); }
    }
    private synchronized boolean prepareCleanup() {
        GraveMarkerCleanup cleanup = plugin.getMarkerCleanup();
        if (cleanup == null) return true;
        cleanupTicket = cleanup.prepare(gravesConfig); return cleanupTicket != null;
    }
    private synchronized void releaseClaim() {
        claimInProgress = false;
        if (!remove && cleanupTicket != null) { cleanupTicket.close(); cleanupTicket = null; }
        if (!remove && expiryDeadline > 0 && expiryDeadline <= System.currentTimeMillis()) removeGrave(GraveRemoveReason.EXPIRED);
    }
    private void cancelClaim(String identity) {
        plugin.getGraveClaimLedger().cancel(identity).whenComplete((saved, failure) -> releaseClaim());
    }
    private synchronized void cleanupMarker() {
        removeHologram();
        GraveMarkerCleanup cleanup = plugin.getMarkerCleanup();
        if (cleanup != null) {
            GraveMarkerCleanup.Ticket ticket = cleanupTicket; cleanupTicket = null;
            if (ticket != null) ticket.commit(); else cleanup.retain(gravesConfig);
            return;
        }
        // Legacy API/test seam when the selected lifecycle has not initialized.
        if (gravesConfig.getLocation().getWorld() == null || !gravesConfig.getLocation().getWorld().isChunkLoaded(gravesConfig.getLocation().getBlockX() >> 4, gravesConfig.getLocation().getBlockZ() >> 4)) return;
        if (gravesConfig.getLocation().getWorld() == null) return;
        // Cleanup does not force-load a chunk; a stored removed grave is already inaccessible.
        Location loc = gravesConfig.getLocation();
        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return;
        Block block = loc.getBlock();
        Object owner = block.getMetadata("Grave").stream().filter(m -> m.getOwningPlugin() == plugin).map(org.bukkit.metadata.MetadataValue::value).findFirst().orElse(null);
        if (owner != this) return;
        if (itemDisplay != null) { itemDisplay.remove(); itemDisplay = null; }
        block.removeMetadata("Grave", plugin);
        if (isValid()) block.setType(Material.AIR);
    }
    /** Invoked only after a matching chunk-load event; never forces a chunk. */
    public void cleanupDeferredMarker() { if (remove) cleanupMarker(); }

	public boolean legacyClaimAllowed(Player player) {
        return isOwner(player) || (!plugin.getConfigFile().isBreakOtherGravesRequireBreakTime() && legacyBreakAllowed(player));
    }
    /** Rights shared by starting, progressing and completing the physical timed-break path. */
    public boolean legacyBreakAllowed(Player player) {
		if (isOwner(player)) return true;
		if (!plugin.getConfigFile().isBreakOtherGravesEnabled()) return false;
		if (plugin.getConfigFile().isBreakOtherGravesRequirePermission()
				&& !player.hasPermission("GraveStonesPlus.BreakOtherGraves")) return false;
		return canNonOwnerClaim();
	}

    public void removeCompass() { plugin.getGraveNavigation().invalidate(GraveIdentity.of(gravesConfig)); }

	public void checkBlockDisplay() {
		boolean hasDisplayEntities = true;
		try {
			@SuppressWarnings("unused")
			EntityType type = EntityType.ITEM_DISPLAY;
		} catch (NoSuchFieldError e) {
			hasDisplayEntities = false;
		}
		if (!hasDisplayEntities) {
			itemDisplay = null;
			return;
		}

		if (getStoredDisplayTypeOrDefault() != GraveDisplayType.DISPLAY_ENTITY) {
			itemDisplay = null;
			return;
		}

		if (!gravesConfig.getLocation().getBlock().getType().equals(Material.BARRIER)) {
			return;
		}

		if (gravesConfig.getDisplayUUID() != null) {
			Entity e = Bukkit.getEntity(gravesConfig.getDisplayUUID());
			if (e instanceof ItemDisplay && !e.isDead()) {
				itemDisplay = (ItemDisplay) e;
			}
		}

		if (itemDisplay == null) {
			itemDisplay = plugin.getGraveDisplayEntityHandler().getItemDisplay(this);
			if (itemDisplay != null && itemDisplay.isDead()) {
				itemDisplay = null;
			}
		}

		if (itemDisplay == null) {
			try {
				ItemDisplay created = plugin.getGraveDisplayEntityHandler().createDisplay(this);
				if (created != null) {
					itemDisplay = created;
					gravesConfig.setDisplayUUID(created.getUniqueId());
					created.getPersistentDataContainer().set(plugin.getKey(), PersistentDataType.INTEGER, 1);
				}
			} catch (Exception e) {
				plugin.debug(e);
			}
		}
        tagDisplay();
	}
}
