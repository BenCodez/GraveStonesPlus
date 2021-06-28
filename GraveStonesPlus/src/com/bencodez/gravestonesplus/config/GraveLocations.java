package com.bencodez.gravestonesplus.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.advancedcore.api.yml.YMLFile;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.gravestonesplus.graves.GravesConfig;

public class GraveLocations extends YMLFile {
	public GraveLocations(AdvancedCorePlugin plugin) {
		super(plugin, new File(plugin.getDataFolder(), "Graves.yml"), true);
	}

	@Override
	public void loadValues() {
	}

	@Override
	public void onFileCreation() {
	}

	public void setGraves(List<Grave> graves) {
		ArrayList<GravesConfig> gravesConfig = new ArrayList<GravesConfig>();
		for (Grave grave : graves) {
			gravesConfig.add(grave.getGravesConfig());
		}
		setGrave(gravesConfig);
	}

	public void setGrave(List<GravesConfig> grave) {
		setValue("graves", grave);
	}

	@SuppressWarnings("unchecked")
	public List<GravesConfig> loadGraves() {
		if (getData() != null) {
			return (List<GravesConfig>) getData().getList("graves");
		} else {
			getPlugin().debug("Graves.yml: Data == null");
			return new ArrayList<GravesConfig>();
		}
	}
}
