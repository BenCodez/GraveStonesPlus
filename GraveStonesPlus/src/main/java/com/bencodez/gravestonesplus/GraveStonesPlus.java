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
import com.bencodez.gravestonesplus.breaking.OtherPlayerBreakManager;
import com.bencodez.gravestonesplus.commands.CommandLoader;
import com.bencodez.gravestonesplus.commands.executor.CommandGraveStonesPlus;
import com.bencodez.gravestonesplus.commands.tabcomplete.GraveStonesPlusTabCompleter;
import com.bencodez.gravestonesplus.config.Config;
import com.bencodez.gravestonesplus.events.GraveRemoveReason;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GraveDisplayEntityHandle;
import com.bencodez.gravestonesplus.listeners.GraveBlockProtectionListener;
import com.bencodez.gravestonesplus.listeners.GraveClaimListener;
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
	private OtherPlayerBreakManager otherPlayerBreakManager;

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
		if (grave == null || grave.getGravesConfig() == null) {
			return;
		}

		grave.getGravesConfig().setDestroyed(false);

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
	}

	private boolean containsByKey(List<Grave> list, Grave grave) {
		if (list == null || grave == null || grave.getGravesConfig() == null
				|| grave.getGravesConfig().getLocation() == null
				|| grave.getGravesConfig().getLocation().getWorld() == null) {
			return false;
		}
		String key = buildGraveKey(grave);
		for (Grave g : list) {
			if (g != null && g.getGravesConfig() != null && key.equals(buildGraveKey(g))) {
				return true;
			}
		}
		return false;
	}

	private void removeFromListByKey(List<Grave> list, Grave grave) {
		if (list == null || grave == null || grave.getGravesConfig() == null
				|| grave.getGravesConfig().getLocation() == null
				|| grave.getGravesConfig().getLocation().getWorld() == null) {
			return;
		}
		String key = buildGraveKey(grave);
		for (int i = list.size() - 1; i >= 0; i--) {
			Grave g = list.get(i);
			if (g != null && g.getGravesConfig() != null && key.equals(buildGraveKey(g))) {
				list.remove(i);
			}
		}
	}

	public void callEventSync(final Event event) {
		if (Bukkit.isPrimaryThread()) {
			Bukkit.getPluginManager().callEvent(event);
		} else {
			Bukkit.getScheduler().runTask(this, new Runnable() {

				@Override
				public void run() {
					Bukkit.getPluginManager().callEvent(event);
				}
			});
		}
	}

	private String buildGraveKey(Grave grave) {
		GravesConfig cfg = grave.getGravesConfig();
		return cfg.getUuid().toString() + "|" + cfg.getLocation().getWorld().getUID().toString() + "|"
				+ cfg.getLocation().getBlockX() + "," + cfg.getLocation().getBlockY() + ","
				+ cfg.getLocation().getBlockZ() + "|" + cfg.getTime();
	}

	@Override
	public void onPostLoad() {
		graveDisplayEntityHandler = new GraveDisplayEntityHandle(this);

		graves = Collections.synchronizedList(new ArrayList<Grave>());
		brokenGraves = Collections.synchronizedList(new ArrayList<Grave>());

		storageManager = new GraveStorageManager(this);
		storageManager.init();

		// Load broken graves
		plugin.getBukkitScheduler().runTask(plugin, new Runnable() {
			@Override
			public void run() {
				List<GravesConfig> gravesbroken1 = storageManager.loadBrokenGraves();
				if (gravesbroken1 != null) {
					for (GravesConfig gr : gravesbroken1) {
						Grave grave = new Grave(plugin, gr);
						try {
							grave.loadChunk(false);
						} catch (Exception e) {
							e.printStackTrace();
						}
						grave.removeHologramsAround();
						if (gr.isDestroyed()) {
							if (gr.getDestroyedTime() == 0) {
								gr.setDestroyedTime(System.currentTimeMillis());
								debug("Fixed broken grave time: " + grave.getGravesConfig().getLocation());
								debug("Broken Grave loaded: " + grave.getGravesConfig().getLocation());
								brokenGraves.add(grave);
							} else {
								ParsedDuration duration = ParsedDuration.parse(configFile.getBrokenGraveTimeLimit(),
										TimeUnit.HOURS);
								long limitMillis = duration.getMillis();

								if (System.currentTimeMillis() - gr.getDestroyedTime() > limitMillis) {
									debug("Broken Grave at " + grave.getGravesConfig().getLocation()
											+ " has reached it's time limit and will be removed");
								} else {
									debug("Broken Grave loaded: " + grave.getGravesConfig().getLocation());
									brokenGraves.add(grave);
								}
							}
						}
					}
					gravesConfig.setBrokenGraves(brokenGraves);
				}
			}
		});

		// Load active graves
		plugin.getBukkitScheduler().runTask(plugin, new Runnable() {
			@Override
			public void run() {
				List<GravesConfig> graves1 = storageManager.loadGraves();
				if (graves1 != null) {
					for (GravesConfig gr : graves1) {
						Grave grave = new Grave(plugin, gr);
						try {
							grave.loadChunk(false);
						} catch (Exception e) {
							e.printStackTrace();
						}
						grave.removeHologramsAround();
						grave.loadBlockMeta(gr.getLocation().getBlock());

						// Display-entities: ensure display entity is present if block is barrier
						grave.checkBlockDisplayAndFixIfMissing();

						if (grave.isValid()) {
							grave.createHologram();
							grave.checkTimeLimit(getConfigFile().getGraveTimeLimit());
							graves.add(grave);
							debug("Grave loaded: " + grave.getGravesConfig().getLocation());

						} else {
							// Only remove if it truly isn't a grave anymore
							grave.removeGrave(GraveRemoveReason.INVALID);
							debug("Grave at " + grave.getGravesConfig().getLocation() + " is not valid");
						}
					}

					// For flatfile, save once after load to persist any displayUUID fixes
					if (!storageManager.isMySQL()) {
						storageManager.saveGravesFromObjects(graves);
					}
				}
			}
		});

		commandLoader = new CommandLoader(this);
		commandLoader.loadCommands();

		otherPlayerBreakManager = new OtherPlayerBreakManager(this);
		otherPlayerBreakManager.start();

		Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(this), this);
		Bukkit.getPluginManager().registerEvents(new GraveClaimListener(this), this);
		Bukkit.getPluginManager().registerEvents(new GraveBlockProtectionListener(this), this);

		plugin.debug("Graves type: " + configFile.getGraveDisplayTypeEnum().name());

		getCommand("gravestonesplus").setExecutor(new CommandGraveStonesPlus(this));
		getCommand("gravestonesplus").setTabCompleter(new GraveStonesPlusTabCompleter(this));

		if (getConfigFile().isGlowingEffectNearGrave()) {
			new Timer().schedule(new TimerTask() {
				@Override
				public void run() {
					if (getConfigFile().isGlowingEffectNearGrave() && plugin != null) {
						for (int i = graves.size() - 1; i >= 0; i--) {
							Grave grave = graves.get(i);
							if (!grave.isRemove()) {
								if (grave.isValid()) {
									grave.checkGlowing();
								} else {
									Bukkit.getScheduler().runTask(plugin, new Runnable() {
										@Override
										public void run() {
											grave.removeGrave(GraveRemoveReason.INVALID);
											grave.removeHologramsAround();
										}
									});
								}
							}
						}
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

		if (otherPlayerBreakManager != null) {
			otherPlayerBreakManager.stop();
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
		// For flatfile, persist lists once at shutdown.
		if (storageManager != null && !storageManager.isMySQL()) {
			storageManager.saveBrokenGravesFromObjects(brokenGraves);
			storageManager.saveGravesFromObjects(graves);
		}

		if (storageManager != null) {
			storageManager.close();
		}

		for (Grave grave : graves) {
			grave.removeHologram();
			grave.removeTimer();
		}

		plugin = null;
	}

	@Override
	public void reload() {
		configFile.reloadData();
		gravesConfig.reloadData();
		nbtConfigManager.reloadConfig();
		updateAdvancedCoreHook();
		reloadAdvancedCore(false);
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
		for (Grave grave : getGraves()) {
			if (grave.getGravesConfig().getUuid().equals(uuid)) {
				num++;
			}
		}
		return num;
	}

	public Grave getOldestGrave(UUID uuid) {
		Grave oldest = null;
		for (Grave grave : getGraves()) {
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
		for (Grave grave : getGraves()) {
			if (grave.isOwner(player)) {
				ownedGraves.add(grave);
			}
		}
		return ownedGraves;
	}

	public List<Grave> getBrokenGraves(Player player) {
		ArrayList<Grave> ownedGraves = new ArrayList<Grave>();
		for (Grave grave : getBrokenGraves()) {
			if (grave.isOwner(player)) {
				ownedGraves.add(grave);
			}
		}
		return ownedGraves;
	}

	public List<Grave> getGraves(String player) {
		ArrayList<Grave> ownedGraves = new ArrayList<Grave>();
		for (Grave grave : getGraves()) {
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
}