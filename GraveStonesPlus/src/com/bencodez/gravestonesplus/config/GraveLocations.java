package com.bencodez.gravestonesplus.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
			return getData().getMapList("graves").stream()
					.map(serializedStat -> GravesConfig.deserialize((Map<String, Object>) serializedStat))
					.collect(Collectors.toList());
		} else {
			return new ArrayList<GravesConfig>();
		}
	}
}
