package com.bencodez.gravestonesplus.storage;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.Grave;

/**
 * Storage manager that selects and delegates to the configured backend.
 */
public class GraveStorageManager {

	private final GraveStonesPlus plugin;
	private GraveStorageBackend backend;
	private DataStorageType type = DataStorageType.FLATFILE;

	/**
	 * Construct a storage manager.
	 *
	 * @param plugin plugin
	 */
	public GraveStorageManager(GraveStonesPlus plugin) {
		this.plugin = plugin;
	}

	/**
	 * Initialize backend based on config.
	 */
	public void init() {
		type = DataStorageType.parse(plugin.getConfigFile().getData().getString("DataStorage", "FLATFILE"));

		if (type == DataStorageType.MYSQL) {
			ConfigurationSection dbSection = plugin.getConfigFile().getData().getConfigurationSection("Database");
			if (dbSection == null) {
				plugin.getLogger().severe("Database section missing, falling back to FLATFILE");
				backend = new FlatFileGraveStorageBackend(new GraveLocations(plugin));
			} else {
				String tableName = "gravestones_graves";
				backend = new MySQLGraveStorageBackend(plugin, tableName, dbSection);
			}
		} else {
			backend = new FlatFileGraveStorageBackend(new GraveLocations(plugin));
		}

		backend.init();
	}

	/**
	 * Load active graves.
	 *
	 * @return list of configs
	 */
	public List<GravesConfig> loadGraves() {
		return backend != null ? backend.loadGraves() : new ArrayList<GravesConfig>();
	}

	/**
	 * Load broken graves.
	 *
	 * @return list of configs
	 */
	public List<GravesConfig> loadBrokenGraves() {
		return backend != null ? backend.loadBrokenGraves() : new ArrayList<GravesConfig>();
	}

	/**
	 * Incremental broken flag update. For flatfile this is a no-op here (flatfile
	 * is saved via bulk list).
	 *
	 * @param grave  config
	 * @param broken broken flag
	 */
	public void setBroken(GravesConfig grave, boolean broken) {
		if (backend != null && isMySQL()) {
			backend.setBroken(grave, broken);
		}
	}

	/**
	 * Save active graves from Grave objects.
	 *
	 * @param graves active grave objects
	 */
	public void saveGravesFromObjects(List<Grave> graves) {
		List<GravesConfig> list = new ArrayList<GravesConfig>();
		if (graves != null) {
			for (Grave g : graves) {
				if (g != null && g.getGravesConfig() != null) {
					list.add(g.getGravesConfig());
				}
			}
		}
		if (backend != null) {
			backend.saveGraves(list);
		}
	}

	/**
	 * @return true if using MySQL backend
	 */
	public boolean isMySQL() {
		return type == DataStorageType.MYSQL;
	}

	/**
	 * Incremental save for a single grave. For flatfile this is a no-op here
	 * (flatfile is saved via bulk list).
	 *
	 * @param grave  config
	 * @param broken broken flag
	 */
	public void upsertGrave(GravesConfig grave, boolean broken) {
		if (backend != null && isMySQL()) {
			backend.upsertGrave(grave, broken);
		}
	}

/**
 * Update an existing grave row (no insert). Intended for state transitions such as removal.
 *
 * @param grave  config
 * @param broken broken flag
 */
public void updateExistingGrave(GravesConfig grave, boolean broken) {
	if (backend != null && isMySQL()) {
		backend.updateExistingGrave(grave, broken);
	}
}



	/**
	 * Save broken graves from Grave objects.
	 *
	 * @param brokenGraves broken grave objects
	 */
	public void saveBrokenGravesFromObjects(List<Grave> brokenGraves) {
		List<GravesConfig> list = new ArrayList<GravesConfig>();
		if (brokenGraves != null) {
			for (Grave g : brokenGraves) {
				if (g != null && g.getGravesConfig() != null) {
					list.add(g.getGravesConfig());
				}
			}
		}
		if (backend != null) {
			backend.saveBrokenGraves(list);
		}
	}

	/**
	 * Close backend.
	 */
	public void close() {
		if (backend != null) {
			backend.close();
		}
	}
}