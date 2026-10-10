package com.bencodez.gravestonesplus.storage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.bencodez.advancedcore.AdvancedCorePlugin;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;
import com.bencodez.simpleapi.file.YMLFile;

public class GraveLocations extends YMLFile {
	public GraveLocations(AdvancedCorePlugin plugin) {
		super(plugin, new File(plugin.getDataFolder(), "Graves.yml"), true);
	}

    private GraveLocations(AdvancedCorePlugin plugin, org.bukkit.configuration.file.FileConfiguration prepared) {
        super(plugin, new File(plugin.getDataFolder(), "Graves.yml"), plugin.getBukkitScheduler(), false);
        setData(prepared);
    }
    public static GraveLocations prepare(AdvancedCorePlugin plugin, org.bukkit.configuration.file.FileConfiguration data) {
        return new GraveLocations(plugin, data);
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

	public void setBrokenGrave(List<GravesConfig> grave) {
		setValue("brokengraves", grave);
	}

	/** Update both sections in memory, then perform the single checked atomic publication. */
	public void saveSnapshot(List<GravesConfig> active, List<GravesConfig> broken) {
		getData().set("brokengraves", broken != null ? broken : new ArrayList<GravesConfig>());
		getData().set("graves", active != null ? active : new ArrayList<GravesConfig>());
        try {
            com.bencodez.gravestonesplus.graves.DurableFeatureState.publishDocument(
                    getdFile().toPath(), getData().saveToString());
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException("Failed to publish complete grave state", failure);
        }
	}

	public void setBrokenGraves(List<Grave> brokenGraves) {
		ArrayList<GravesConfig> gravesConfig = new ArrayList<GravesConfig>();
		for (Grave grave : brokenGraves) {
			gravesConfig.add(grave.getGravesConfig());
		}
		setBrokenGrave(gravesConfig);
	}

	@SuppressWarnings("unchecked")
	public List<GravesConfig> loadGraves() {
		if (getData() != null) {
			return (List<GravesConfig>) getData().getList("graves");
		} else {
			GraveStonesPlus.getPlugin().debug("Graves.yml: Data == null");
			return new ArrayList<GravesConfig>();
		}
	}

	@SuppressWarnings("unchecked")
	public List<GravesConfig> loadBrokenGraves() {
		if (getData() != null) {
			return (List<GravesConfig>) getData().getList("brokengraves");
		} else {
			GraveStonesPlus.getPlugin().debug("Graves.yml: Data == null");
			return new ArrayList<GravesConfig>();
		}
	}
}
