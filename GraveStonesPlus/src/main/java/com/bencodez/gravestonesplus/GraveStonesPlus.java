package com.bencodez.gravestonesplus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.gravestonesplus.commands.CommandLoader;
import com.bencodez.gravestonesplus.commands.executor.CommandGraveStonesPlus;
import com.bencodez.gravestonesplus.commands.tabcomplete.GraveStonesPlusTabCompleter;
import com.bencodez.gravestonesplus.config.Config;
import com.bencodez.gravestonesplus.config.GraveLocations;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GraveDisplayEntityHandle;
import com.bencodez.gravestonesplus.graves.GravesConfig;
import com.bencodez.gravestonesplus.listeners.PlayerBreakBlock;
import com.bencodez.gravestonesplus.listeners.PlayerDeathListener;
import com.bencodez.gravestonesplus.listeners.PlayerInteract;
import com.bencodez.gravestonesplus.pluginhandles.PvpManagerHandle;
import com.bencodez.gravestonesplus.pluginhandles.SlimefunHandle;
import com.bencodez.simpleapi.file.YMLConfig;
import com.bencodez.simpleapi.metrics.BStatsMetrics;
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
	private Set<Grave> brokenGraves;

	public void addGrave(Grave grave) {
		graves.add(grave);
		gravesConfig.setGraves(graves);
	}

	public void removeGrave(Grave grave) {
		graves.remove(grave);
		brokenGraves.add(grave);
		gravesConfig.setGraves(graves);
		gravesConfig.setBrokenGraves(brokenGraves);

	}

	public void recreateBrokenGrave(Grave grave) {
		grave.getGravesConfig().setDestroyed(false);
		graves.add(grave);
		brokenGraves.remove(grave);
		gravesConfig.setGraves(graves);
		gravesConfig.setBrokenGraves(brokenGraves);
	}

	@Getter
	public static GraveStonesPlus plugin;

	@Getter
	@Setter
	private Updater updater;

	@Getter
	@Setter
	private boolean usingDisplayEntities = false;

	@Getter
	private GraveDisplayEntityHandle graveDisplayEntityHandler;

	@Override
	public void onPostLoad() {
		graveDisplayEntityHandler = new GraveDisplayEntityHandle(this);
		graves = Collections.synchronizedList(new ArrayList<Grave>());
		brokenGraves = Collections.synchronizedSet(new HashSet<Grave>());

		plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				List<GravesConfig> gravesbroken1 = gravesConfig.loadBrokenGraves();
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
								if (System.currentTimeMillis()
										- gr.getDestroyedTime() > configFile.getBokenGraveTimeLimit() * 60 * 1000) {
									debug("Broken Grave at " + grave.getGravesConfig().getLocation()
											+ " has reached it's time limit and will be removed");
								} else {
									debug("Broken Grave loaded: " + grave.getGravesConfig().getLocation());
									brokenGraves.add(grave);
								}
							}
						}
					}

					// remove graves from file
					gravesConfig.setBrokenGraves(brokenGraves);
				}
			}
		});

		plugin.getBukkitScheduler().runTask(plugin, new Runnable() {

			@Override
			public void run() {
				List<GravesConfig> graves1 = gravesConfig.loadGraves();
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
						grave.checkBlockDisplay();
						if (grave.isValid()) {
							grave.createHologram();
							grave.checkTimeLimit(getConfigFile().getGraveTimeLimit());
							graves.add(grave);
							debug("Grave loaded: " + grave.getGravesConfig().getLocation());
						} else {
							grave.removeGrave();
							debug("Grave at " + grave.getGravesConfig().getLocation() + " is not valid");
						}
					}
				}
			}
		});

		commandLoader = new CommandLoader(this);
		commandLoader.loadCommands();

		Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(this), this);
		Bukkit.getPluginManager().registerEvents(new PlayerInteract(this), this);
		Bukkit.getPluginManager().registerEvents(new PlayerBreakBlock(this), this);
		if (plugin.getConfigFile().isUseDisplayEntities()) {
			usingDisplayEntities = true;
			// Bukkit.getPluginManager().registerEvents(new PlayerInteractEntity(this),
			// this);
			plugin.debug("Using display entities");
		}

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
											grave.removeGrave();
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

		Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {

			@Override
			public void run() {
				new BStatsMetrics(plugin, 11838);
			}
		});

		plugin.getBukkitScheduler().runTaskAsynchronously(this, new Runnable() {

			@Override
			public void run() {
				setUpdater(new Updater(plugin, 95132, false));
				Updater.UpdateResult result = plugin.getUpdater().getResult();
				switch (result) {
				case FAIL_SPIGOT: {
					plugin.getLogger().info("Failed to check for update for " + plugin.getName() + "!");
					break;
				}
				case NO_UPDATE: {
					plugin.getLogger()
							.info(plugin.getName() + " is up to date! Version: " + plugin.getUpdater().getVersion());
					break;
				}
				case UPDATE_AVAILABLE: {
					plugin.getLogger()
							.info(plugin.getName() + " has an update available! Your Version: "
									+ plugin.getDescription().getVersion() + " New Version: "
									+ plugin.getUpdater().getVersion());
					break;
				}
				default: {
					break;
				}
				}
			}

		});

		plugin.getLogger().info("Enabled GraveStonesPlus!");

	}

	@Getter
	private PvpManagerHandle pvpManager;

	@Getter
	private SlimefunHandle slimefun;

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

	@Override
	public void onPreLoad() {
		plugin = this;
		configFile = new Config(this);
		configFile.reloadData();
		gravesConfig = new GraveLocations(this);

		updateAdvancedCoreHook();
	}

	@Override
	public void onUnLoad() {
		gravesConfig.setGraves(graves);
		gravesConfig.setBrokenGraves(brokenGraves);
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
