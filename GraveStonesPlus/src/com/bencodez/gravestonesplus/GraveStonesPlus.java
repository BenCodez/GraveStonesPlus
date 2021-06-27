package com.bencodez.gravestonesplus;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.configuration.serialization.ConfigurationSerialization;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.gravestonesplus.config.Config;
import com.bencodez.gravestonesplus.config.GraveLocations;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GravesConfig;
import com.bencodez.gravestonesplus.listeners.PlayerBreakBlock;
import com.bencodez.gravestonesplus.listeners.PlayerDeathListener;
import com.bencodez.gravestonesplus.listeners.PlayerInteract;

import lombok.Getter;

public class GraveStonesPlus extends AdvancedCorePlugin {

	static {
		ConfigurationSerialization.registerClass(GravesConfig.class);
	}

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

	@Override
	public void onPostLoad() {
		graves = new ArrayList<Grave>();
		for (GravesConfig gr : gravesConfig.loadGraves()) {
			graves.add(new Grave(gr));
		}

		Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(this), this);
		Bukkit.getPluginManager().registerEvents(new PlayerInteract(this), this);
		Bukkit.getPluginManager().registerEvents(new PlayerBreakBlock(this), this);
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

		plugin = null;
	}

	@Override
	public void reload() {
		configFile.reloadData();
		updateAdvancedCoreHook();
	}

	private void updateAdvancedCoreHook() {
		getJavascriptEngine().put("GraveStonesPlus", this);
		setConfigData(configFile.getData());
	}

	@Getter
	private Config configFile;

	@Getter
	private GraveLocations gravesConfig;

}
