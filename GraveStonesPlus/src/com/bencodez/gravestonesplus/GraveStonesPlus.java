package com.bencodez.gravestonesplus;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import org.bukkit.Bukkit;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.entity.Player;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.command.CommandHandler;
import com.bencodez.advancedcore.api.metrics.BStatsMetrics;
import com.bencodez.advancedcore.api.updater.Updater;
import com.bencodez.gravestonesplus.commands.CommandLoader;
import com.bencodez.gravestonesplus.commands.executor.CommandGraveStonesPlus;
import com.bencodez.gravestonesplus.commands.tabcomplete.GraveStonesPlusTabCompleter;
import com.bencodez.gravestonesplus.config.Config;
import com.bencodez.gravestonesplus.config.GraveLocations;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GravesConfig;
import com.bencodez.gravestonesplus.listeners.PlayerBreakBlock;
import com.bencodez.gravestonesplus.listeners.PlayerDeathListener;
import com.bencodez.gravestonesplus.listeners.PlayerInteract;

import lombok.Getter;
import lombok.Setter;

public class GraveStonesPlus extends AdvancedCorePlugin {

	static {
		ConfigurationSerialization.registerClass(GravesConfig.class);
	}

	@Getter
	private ArrayList<CommandHandler> commands = new ArrayList<CommandHandler>();

	@Getter
	private CommandLoader commandLoader;

	@Getter
	private List<Grave> graves;

	public void addGrave(Grave grave) {
		graves.add(grave);
		gravesConfig.setGraves(graves);
	}

	public void removeGrave(Grave grave) {
		graves.remove(grave);
		gravesConfig.setGraves(graves);
	}

	@Getter
	public static GraveStonesPlus plugin;

	@Getter
	@Setter
	private Updater updater;

	@Override
	public void onPostLoad() {
		graves = new ArrayList<Grave>();
		for (GravesConfig gr : gravesConfig.loadGraves()) {
			Grave grave = new Grave(gr);
			if (grave.isValid()) {
				grave.createHologram();
				grave.checkTimeLimit(getConfigFile().getGraveTimeLimit());
				graves.add(grave);
				debug("Grave loaded: " + grave.getGravesConfig().getLocation());
			} else {
				debug("Grave at " + grave.getGravesConfig().getLocation() + " is not valid");
			}
		}

		commandLoader = new CommandLoader(this);
		commandLoader.loadCommands();

		Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(this), this);
		Bukkit.getPluginManager().registerEvents(new PlayerInteract(this), this);
		Bukkit.getPluginManager().registerEvents(new PlayerBreakBlock(this), this);

		getCommand("gravestonesplus").setExecutor(new CommandGraveStonesPlus(this));
		getCommand("gravestonesplus").setTabCompleter(new GraveStonesPlusTabCompleter(this));

		new Timer().schedule(new TimerTask() {

			@Override
			public void run() {
				if (getConfigFile().isGlowingEffectNearGrave() && plugin != null) {
					for (Grave grave : getGraves()) {
						grave.checkGlowing();
					}
				} else {
					cancel();
				}
			}
		}, 1000 * 10, 1000 * 5);

		new BStatsMetrics(plugin, 11838);

		Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {

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

	}

	@Override
	public void onPreLoad() {
		plugin = this;
		configFile = new Config(this);
		gravesConfig = new GraveLocations(this);

		updateAdvancedCoreHook();
	}

	@Override
	public void onUnLoad() {
		gravesConfig.setGraves(graves);
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
		setConfigData(configFile.getData());
		setLoadUserData(false);
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
