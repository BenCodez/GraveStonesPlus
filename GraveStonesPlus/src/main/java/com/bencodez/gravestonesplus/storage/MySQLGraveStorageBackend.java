package com.bencodez.gravestonesplus.storage;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.ConfigurationSection;

import com.bencodez.advancedcore.AdvancedCorePlugin;

/**
 * MySQL grave storage backend.
 *
 * Uses incremental writes for changes, but still supports loadAll.
 */
public class MySQLGraveStorageBackend implements GraveStorageBackend {

	private final AdvancedCorePlugin plugin;
	private final String tableName;
	private final ConfigurationSection databaseSection;

	private GravesMySQLTable table;

	/**
	 * Construct MySQL backend.
	 *
	 * @param plugin          plugin
	 * @param tableName       table name
	 * @param databaseSection Database section from config
	 */
	public MySQLGraveStorageBackend(AdvancedCorePlugin plugin, String tableName, ConfigurationSection databaseSection) {
		this.plugin = plugin;
		this.tableName = tableName;
		this.databaseSection = databaseSection;
	}

	@Override
	public void init() {
		table = new GravesMySQLTable(plugin, tableName, databaseSection);
	}

	@Override
	public List<GravesConfig> loadGraves() {
		return table != null ? table.loadAll(false) : new ArrayList<GravesConfig>();
	}

	@Override
	public List<GravesConfig> loadBrokenGraves() {
		return table != null ? table.loadAll(true) : new ArrayList<GravesConfig>();
	}

    List<java.util.function.Supplier<GravesConfig>> loadDeferred(boolean broken) {
        return table != null ? table.loadDeferred(broken) : List.of();
    }

	@Override
	public void saveGraves(List<GravesConfig> graves) {
		// Bulk is intentionally not used for MySQL.
		// Use upsertGrave / setBroken for incremental writes.
	}

	@Override
	public void saveBrokenGraves(List<GravesConfig> graves) {
		// Bulk is intentionally not used for MySQL.
		// Use upsertGrave / setBroken for incremental writes.
	}

	@Override
	public void upsertGrave(GravesConfig grave, boolean broken) {
        if (table == null) throw new IllegalStateException("MySQL grave table unavailable");
        table.upsertGraveChecked(grave, broken);
	}

    @Override public void saveDisplayIdentity(GravesConfig grave) {
        if (table == null) throw new IllegalStateException("MySQL grave table unavailable");
        table.saveDisplayIdentity(grave);
    }

    @Override public void saveRecreatedGrave(GravesConfig grave) {
        if (table == null) throw new IllegalStateException("MySQL grave table unavailable");
        table.saveRecreatedGrave(grave);
    }

	@Override
	public void setBroken(GravesConfig grave, boolean broken) {
        if (table == null) throw new IllegalStateException("MySQL grave table unavailable");
        table.setBrokenChecked(grave, broken);
	}

	@Override
	public void updateExistingGrave(GravesConfig grave, boolean broken) {
        if (table == null) throw new IllegalStateException("MySQL grave table unavailable");
        table.updateExistingGraveChecked(grave, broken);
	}

	@Override
	public void deleteGrave(GravesConfig grave) {
        if (table == null) throw new IllegalStateException("MySQL grave table unavailable");
        table.deleteGraveChecked(grave);
	}

	@Override
	public void close() {
		// keep pool for plugin lifetime; handled by AdvancedCore/plugin lifecycle
	}
}