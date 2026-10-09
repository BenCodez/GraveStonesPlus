package com.bencodez.gravestonesplus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.gravestonesplus.breaking.PlayerBreakManager;
import com.bencodez.gravestonesplus.commands.CommandLoader;
import com.bencodez.gravestonesplus.commands.executor.CommandGraveStonesPlus;
import com.bencodez.gravestonesplus.commands.tabcomplete.GraveStonesPlusTabCompleter;
import com.bencodez.gravestonesplus.config.Config;
import com.bencodez.gravestonesplus.events.GraveRemoveReason;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GraveDisplayEntityHandle;
import com.bencodez.gravestonesplus.graves.TrustedGraveAccess;
import com.bencodez.gravestonesplus.graves.TrustedGraveAccessStore;
import com.bencodez.gravestonesplus.graves.GraveReminderStore;
import com.bencodez.gravestonesplus.listeners.GraveBlockProtectionListener;
import com.bencodez.gravestonesplus.listeners.GraveClaimListener;
import com.bencodez.gravestonesplus.listeners.GraveCleanupListener;
import com.bencodez.gravestonesplus.listeners.PlayerDeathListener;
import com.bencodez.gravestonesplus.nbt.NBTConfigManager;
import com.bencodez.gravestonesplus.pluginhandles.PvpManagerHandle;
import com.bencodez.gravestonesplus.pluginhandles.SlimefunHandle;
import com.bencodez.gravestonesplus.storage.GraveLocations;
import com.bencodez.gravestonesplus.storage.GraveStorageManager;
import com.bencodez.gravestonesplus.storage.GravesConfig;
import com.bencodez.simpleapi.file.YMLConfig;
import com.bencodez.simpleapi.metrics.BStatsMetrics;
import com.bencodez.simpleapi.time.ParsedDuration;
import com.bencodez.simpleapi.updater.Updater;

import lombok.Getter;
import lombok.Setter;

public class GraveStonesPlus extends AdvancedCorePlugin {

	static {
		ConfigurationSerialization.registerClass(GravesConfig.class);
	}

	@Getter
	private ArrayList<CommandHandler> commands = new ArrayList<CommandHandler>();

	@Getter
	private NamespacedKey key = new NamespacedKey(this, "gravestoneplusholograms");

	@Getter
	private CommandLoader commandLoader;

	@Getter
	private List<Grave> graves;

	@Getter
	private List<Grave> brokenGraves;

	@Getter
	private GraveStorageManager storageManager;

	@Getter
	public static GraveStonesPlus plugin;

	@Getter
	@Setter
	private Updater updater;

	@Getter
	private GraveDisplayEntityHandle graveDisplayEntityHandler;

	@Getter
	private NBTConfigManager nbtConfigManager;

	@Getter
	private boolean nbtAPIHooked = false;

	@Getter
	private PlayerBreakManager playerBreakManager;

	@Getter
	private TrustedGraveAccessStore trustedGraveAccessStore;
    @Getter private com.bencodez.gravestonesplus.graves.FeatureIO featureIO;
    @Getter private com.bencodez.gravestonesplus.graves.GraveMarkerCleanup markerCleanup;
    @Getter private com.bencodez.gravestonesplus.graves.GraveRestoration graveRestoration;
    @Getter private com.bencodez.gravestonesplus.graves.GraveClaimLedger graveClaimLedger;
    @Getter private com.bencodez.gravestonesplus.graves.GraveNavigation graveNavigation;

	@Getter
	private GraveReminderStore graveReminderStore;
	private com.bencodez.gravestonesplus.listeners.GraveReminderListener graveReminderListener;

    /** Restored records were already persisted; admission never republishes a partial startup list. */
    public void restoreActiveRecord(Grave grave) { if (!containsByKey(graves, grave)) graves.add(grave); }
    public void restoreBrokenRecord(Grave grave) { if (!containsByKey(brokenGraves, grave)) brokenGraves.add(grave); }

	public void addGrave(Grave grave) {
		if (grave == null || grave.getGravesConfig() == null) {
			return;
		}
		graves.add(grave);

		if (storageManager.isMySQL()) {
			storageManager.upsertGrave(grave.getGravesConfig(), false);
		} else {
			storageManager.saveGravesFromObjects(graves);
		}
	}

	public void removeGrave(Grave grave) {
		if (grave == null || grave.getGravesConfig() == null) {
			return;
		}

		// Remove from active list by key (not object identity)
		removeFromListByKey(graves, grave);

		// Add to broken list only once
		if (!containsByKey(brokenGraves, grave)) {
			brokenGraves.add(grave);
		}

		if (storageManager.isMySQL()) {
			storageManager.updateExistingGrave(grave.getGravesConfig(), true);
		} else {
			storageManager.saveBrokenGravesFromObjects(brokenGraves);
			storageManager.saveGravesFromObjects(graves);
		}
	}

	public void recreateBrokenGrave(Grave grave) {
		recreateBrokenGrave(grave, null);
	}

    public java.util.concurrent.CompletableFuture<Boolean> recreateBrokenGrave(Grave grave, Player actor) {
		if (grave == null || grave.getGravesConfig() == null) {
			return java.util.concurrent.CompletableFuture.completedFuture(false);
		}

        var completion = grave.recreateAsync(actor, actor != null).thenApply(restored -> {
        if (!restored) return false;
		if (!containsByKey(graves, grave)) {
			graves.add(grave);
		}
		removeFromListByKey(brokenGraves, grave);

		if (storageManager.isMySQL()) {
			storageManager.setBroken(grave.getGravesConfig(), false);
		} else {
			storageManager.saveBrokenGravesFromObjects(brokenGraves);
			storageManager.saveGravesFromObjects(graves);
		}
        return true;
        });
		// Caller cancellation/completion affects only observation. The internal stage
		// must still move the lists and request persistence after recreation commits.
		return completion.copy();
	}

	private boolean containsByKey(List<Grave> list, Grave grave) {
		if (list == null || grave == null || grave.getGravesConfig() == null
				|| grave.getGravesConfig().getLocation() == null
				|| grave.getGravesConfig().getWorldId() == null) {
			return false;
		}
		String key = buildGraveKey(grave);
		for (Grave g : snapshot(list)) {
			if (g != null && g.getGravesConfig() != null && key.equals(buildGraveKey(g))) {
				return true;
			}
		}
		return false;
	}

	private void removeFromListByKey(List<Grave> list, Grave grave) {
		if (list == null || grave == null || grave.getGravesConfig() == null
				|| grave.getGravesConfig().getLocation() == null
				|| grave.getGravesConfig().getWorldId() == null) {
			return;
		}
        String key = buildGraveKey(grave);
        synchronized (list) { list.removeIf(g -> g != null && g.getGravesConfig() != null && key.equals(buildGraveKey(g))); }
	}

	public void callEventSync(final Event event) {
		if (Bukkit.isPrimaryThread()) {
			Bukkit.getPluginManager().callEvent(event);
		} else {

			plugin.getBukkitScheduler().runTask(plugin, () -> {
				Bukkit.getPluginManager().callEvent(event);
			});
		}
	}

	private String buildGraveKey(Grave grave) {
		GravesConfig cfg = grave.getGravesConfig();
		return cfg.getUuid().toString() + "|" + cfg.getWorldId().toString() + "|"
				+ cfg.getLocation().getBlockX() + "," + cfg.getLocation().getBlockY() + ","
				+ cfg.getLocation().getBlockZ() + "|" + cfg.getTime();
	}

	@Override
	public void onPostLoad() {
		if (featureIO != null && !featureIO.isTerminated()) throw new IllegalStateException("Previous grave disk owner is still draining; cannot reload selected state yet");
        featureIO = new com.bencodez.gravestonesplus.graves.FeatureIO();
        graveClaimLedger = new com.bencodez.gravestonesplus.graves.GraveClaimLedger(
            new com.bencodez.gravestonesplus.graves.DurableFeatureState(featureIO,
                new java.io.File(getDataFolder(), "selected-claims.yml").toPath(), 8192),
            new java.io.File(getDataFolder(), "selected-claim-archive").toPath());
        markerCleanup = new com.bencodez.gravestonesplus.graves.GraveMarkerCleanup(this,
            new com.bencodez.gravestonesplus.graves.DurableFeatureState(featureIO,
                new java.io.File(getDataFolder(), "selected-marker-cleanup.yml").toPath(), 8192));
        graveNavigation = new com.bencodez.gravestonesplus.graves.GraveNavigation(this);
        trustedGraveAccessStore = new TrustedGraveAccessStore(this);
		graveReminderStore = new GraveReminderStore(this);
		graveDisplayEntityHandler = new GraveDisplayEntityHandle(this);

		graves = Collections.synchronizedList(new ArrayList<Grave>());
		brokenGraves = Collections.synchronizedList(new ArrayList<Grave>());

		storageManager = new GraveStorageManager(this);
		storageManager.init();

        graveRestoration = new com.bencodez.gravestonesplus.graves.GraveRestoration(this, storageManager);
        graveRestoration.start();

		commandLoader = new CommandLoader(this);
		commandLoader.loadCommands();

		playerBreakManager = new PlayerBreakManager(this);
		playerBreakManager.start();

		Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(this), this);
		Bukkit.getPluginManager().registerEvents(new GraveClaimListener(this), this);
		Bukkit.getPluginManager().registerEvents(new GraveCleanupListener(this), this);
		Bukkit.getPluginManager().registerEvents(new GraveBlockProtectionListener(this), this);
		graveReminderListener = new com.bencodez.gravestonesplus.listeners.GraveReminderListener(this);
		Bukkit.getPluginManager().registerEvents(graveReminderListener, this);
		graveReminderListener.start();

		plugin.debug("Graves type: " + configFile.getGraveDisplayTypeEnum().name());

		getCommand("gravestonesplus").setExecutor(new CommandGraveStonesPlus(this));
		getCommand("gravestonesplus").setTabCompleter(new GraveStonesPlusTabCompleter(this));

		if (getConfigFile().isGlowingEffectNearGrave()) {
			new Timer().schedule(new TimerTask() {
				@Override
				public void run() {
					if (getConfigFile().isGlowingEffectNearGrave() && plugin != null) {
                        for (Grave grave : activeGraveSnapshot()) refreshGraveGlow(grave);
					} else {
						cancel();
					}
				}
			}, 1000 * 10, 1000 * 5);
		}

		Permission perm = Bukkit.getPluginManager().getPermission("GraveStonesPlus.BreakOtherGraves");
		if (perm != null) {
			if (configFile.isGiveBreakOtherGravesPermission()) {
				perm.setDefault(PermissionDefault.TRUE);
				getLogger().info(
						"Giving GraveStonesPlus.BreakOtherGraves permission by default, can be disabled in the config");
			} else {
				perm.setDefault(PermissionDefault.OP);
			}
		}

		if (Bukkit.getPluginManager().isPluginEnabled("PvPManager")) {
			pvpManager = new PvpManagerHandle(this);
		}
		if (Bukkit.getPluginManager().isPluginEnabled("Slimefun")) {
			slimefun = new SlimefunHandle(this);
		}

		if (Bukkit.getPluginManager().isPluginEnabled("NBTAPI")) {
			nbtAPIHooked = true;
		} else {
			plugin.getLogger().info("NBTAPI not found, some features may not work.");
			nbtAPIHooked = false;
		}

		plugin.getBukkitScheduler().runTaskAsynchronously(plugin, new Runnable() {
			@Override
			public void run() {
				new BStatsMetrics(plugin, 11838);
			}
		});

		plugin.getBukkitScheduler().runTaskAsynchronously(this, new Runnable() {
			@Override
			public void run() {
				setUpdater(new Updater(plugin, 95132, false));
			}
		});

		plugin.getLogger().info("Enabled GraveStonesPlus!");
	}

	@Getter
	private PvpManagerHandle pvpManager;

	@Getter
	private SlimefunHandle slimefun;

	@Override
	public void onPreLoad() {
		plugin = this;
		configFile = new Config(this);
		configFile.reloadData();
		gravesConfig = new GraveLocations(this);
		nbtConfigManager = new NBTConfigManager(this);
		updateAdvancedCoreHook();
	}

	@Override
	public void onUnLoad() {
		if (graveRestoration != null) graveRestoration.retire();
        if (markerCleanup != null) markerCleanup.retire();
		if (graveReminderListener != null) graveReminderListener.stop();
		if (graveReminderStore != null) graveReminderStore.close();
		if (playerBreakManager != null) {
			playerBreakManager.stop();
		}

		// For flatfile, persist lists once at shutdown.
		if (storageManager != null && !storageManager.isMySQL()) {
			storageManager.saveBrokenGravesFromObjects(brokenGraves);
			storageManager.saveGravesFromObjects(graves);
		}

		if (storageManager != null) {
			storageManager.close();
		}

        if (featureIO != null) featureIO.close();
		for (Grave grave : activeGraveSnapshot()) {
			grave.removeHologram();
			grave.removeTimer();
		}

		plugin = null;
	}

	@Override
    public void reload() {
        if (playerBreakManager != null) playerBreakManager.invalidateAuthorization();
        featureIO.submit(() -> {
            configFile.reloadData(); gravesConfig.reloadData(); nbtConfigManager.reloadConfig(); return true;
        }).whenComplete((loaded, failure) -> {
            if (failure != null) { getLogger().severe("Grave configuration reload failed; retaining current lifecycle state."); return; }
            getBukkitScheduler().runTask(this, () -> {
                updateAdvancedCoreHook(); reloadAdvancedCore(false);
                for (Grave grave : activeGraveSnapshot()) getBukkitScheduler().runTask(this,
                        () -> grave.checkTimeLimit(getConfigFile().getGraveTimeLimit()), grave.getGravesConfig().getLocation());
            });
        });
    }

	private void updateAdvancedCoreHook() {
		getJavascriptEngine().put("GraveStonesPlus", this);
		setConfigData(new YMLConfig(plugin, configFile.getData()) {
			@Override
			public void setValue(String path, Object value) {
				configFile.setValue(path, value);
			}

			@Override
			public void saveData() {
				configFile.saveData();
			}

			@Override
			public void createSection(String key) {
				configFile.createSection(key);
			}
		});
		setLoadUserData(false);
		setLoadVault(false);
	}

	@Getter
	private Config configFile;

	@Getter
	private GraveLocations gravesConfig;

	public int numberOfGraves(UUID uuid) {
		int num = 0;
		for (Grave grave : activeGraveSnapshot()) {
			if (grave.getGravesConfig().getUuid().equals(uuid)) {
				num++;
			}
		}
		return num;
	}

	public Grave getOldestGrave(UUID uuid) {
		Grave oldest = null;
		for (Grave grave : activeGraveSnapshot()) {
            if (grave.isLimitPayoutPending()) continue;
			if (grave.getGravesConfig().getUuid().equals(uuid)) {
				if (oldest == null) {
					oldest = grave;
				}
				if (grave.getGravesConfig().getTime() < oldest.getGravesConfig().getTime()) {
					oldest = grave;
				}
			}
		}
		return oldest;
	}

	public List<Grave> getGraves(Player player) {
		ArrayList<Grave> ownedGraves = new ArrayList<Grave>();
		for (Grave grave : activeGraveSnapshot()) {
			if (grave.isOwner(player)) {
				ownedGraves.add(grave);
			}
		}
		return ownedGraves;
	}

	/** Graves the player may navigate to; recovery is checked separately. */
	public List<Grave> getAccessibleGraves(Player player) {
		ArrayList<Grave> result = new ArrayList<Grave>();
		for (Grave grave : activeGraveSnapshot()) if (canTrack(grave, player)) result.add(grave);
		return result;
	}

	public List<Grave> getBrokenGraves(Player player) {
		ArrayList<Grave> ownedGraves = new ArrayList<Grave>();
		for (Grave grave : brokenGraveSnapshot()) {
			if (grave.isOwner(player)) {
				ownedGraves.add(grave);
			}
		}
		return ownedGraves;
	}

	public List<Grave> getGraves(String player) {
		ArrayList<Grave> ownedGraves = new ArrayList<Grave>();
		for (Grave grave : activeGraveSnapshot()) {
			if (grave.isOwner(player)) {
				ownedGraves.add(grave);
			}
		}
		return ownedGraves;
	}

	public Grave getLatestGrave(Player player) {
		long time = 0;
		Grave cGrave = null;
		for (Grave grave : getGraves(player)) {
			long cTime = grave.getGravesConfig().getTime();
			if (cTime > time) {
				time = cTime;
				cGrave = grave;
			}
		}
		return cGrave;
	}

    private static List<Grave> snapshot(List<Grave> list) { synchronized (list) { return new ArrayList<>(list); } }
    public List<Grave> activeGraveSnapshot() { synchronized (graves) { return new ArrayList<>(graves); } }
    public List<Grave> brokenGraveSnapshot() { synchronized (brokenGraves) { return new ArrayList<>(brokenGraves); } }
    /** Startup logical records may be unloaded; native glow/marker work stays on their region. */
    public void refreshGraveGlow(Grave grave) {
        org.bukkit.Location location = grave.getGravesConfig().getLocation();
        if (location == null || location.getWorld() == null) return;
        getBukkitScheduler().runTask(this, () -> {
            if (!isEnabled() || grave.isRemove() || grave.getGravesConfig().isDestroyed()
                    || !getConfigFile().isGlowingEffectNearGrave()
                    || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
            if (grave.isValid()) grave.checkGlowing();
            else grave.removeGrave(GraveRemoveReason.INVALID);
        }, location);
    }
    /** Eligibility reads only stored state; never probes blocks or loads chunks. */
    public boolean isLiveGrave(Grave grave) {
        if (grave == null || grave.isRemove() || grave.getGravesConfig().isDestroyed()
                || com.bencodez.gravestonesplus.graves.GraveIdentity.of(grave.getGravesConfig()) == null) return false;
        long deadline = grave.getExpiryDeadline();
        if (deadline > 0 && deadline <= System.currentTimeMillis()) return false;
        synchronized (graves) { return graves.contains(grave); }
    }
    public boolean canRecover(Grave grave, Player player) {
        return isLiveGrave(grave) && player != null && (grave.isOwner(player)
                || trustedGraveAccessStore.allows(com.bencodez.gravestonesplus.graves.GraveIdentity.of(grave.getGravesConfig()), player.getUniqueId(), TrustedGraveAccess.Kind.RECOVER));
    }
    public boolean canTrack(Grave grave, Player player) {
        return isLiveGrave(grave) && player != null && !graveClaimLedger.isBlocked(com.bencodez.gravestonesplus.graves.GraveIdentity.of(grave.getGravesConfig()))
                && (grave.isOwner(player) || trustedGraveAccessStore.allows(com.bencodez.gravestonesplus.graves.GraveIdentity.of(grave.getGravesConfig()), player.getUniqueId(), TrustedGraveAccess.Kind.TRACK));
    }
}
