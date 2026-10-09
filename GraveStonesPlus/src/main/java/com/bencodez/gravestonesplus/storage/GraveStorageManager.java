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
    private record Recovery(List<GravesConfig> active, List<GravesConfig> broken) { }
    private Recovery retained;
    private boolean recovering;
    private boolean restorationLoading;
    public synchronized void beginRestoration() { restorationLoading = true; }
    private final java.util.Map<String, GravesConfig> restoringActive = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, GravesConfig> restoringBroken = new java.util.LinkedHashMap<>();
    /** Backend records remain authoritative until a region admission resolves them. */
    public synchronized void retainRestoration(List<GravesConfig> active, List<GravesConfig> broken) {
        for (GravesConfig grave : active) restoringActive.put(restorationKey(grave), snapshot(grave));
        for (GravesConfig grave : broken) restoringBroken.put(restorationKey(grave), snapshot(grave));
        restorationLoading = false;
        if (retained != null) retained = recoverySnapshot();
    }
    private String restorationKey(GravesConfig grave) {
        String id = com.bencodez.gravestonesplus.graves.GraveIdentity.of(grave);
        return id == null ? "unresolved-" + java.util.UUID.randomUUID() : id;
    }
    public synchronized void restorationResolved(GravesConfig grave) {
        String id = com.bencodez.gravestonesplus.graves.GraveIdentity.of(grave);
        restoringActive.remove(id); restoringBroken.remove(id);
    }
    private List<GravesConfig> preserving(List<GravesConfig> current, boolean broken) {
        java.util.Map<String, GravesConfig> result = new java.util.LinkedHashMap<>(broken ? restoringBroken : restoringActive);
        for (GravesConfig grave : current) result.put(restorationKey(grave), grave);
        return List.copyOf(result.values());
    }

	/**
	 * Construct a storage manager.
	 *
	 * @param plugin plugin
	 */
	public GraveStorageManager(GraveStonesPlus plugin) {
		this.plugin = plugin;
        plugin.getFeatureIO().onDrain(this::flushRetained);
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
	public synchronized void setBroken(GravesConfig grave, boolean broken) {
		if (backend != null && isMySQL()) {
			GravesConfig snapshot = snapshot(grave);
            enqueue(() -> backend.setBroken(snapshot, broken));
		}
	}

	/**
	 * Save active graves from Grave objects.
	 *
	 * @param graves active grave objects
	 */
	public synchronized void saveGravesFromObjects(List<Grave> graves) {
		List<GravesConfig> list = new ArrayList<GravesConfig>();
		if (graves != null) {
			for (Grave g : copy(graves)) {
				if (g != null && g.getGravesConfig() != null) {
					list.add(snapshot(g.getGravesConfig()));
				}
			}
		}
		if (backend != null) {
            if (restorationLoading) { retained = recoverySnapshot(); return; }
            // Compose before admission: later region resolution cannot erase a record
            // from a previously captured snapshot. Following removal snapshots win in order.
            List<GravesConfig> safe = preserving(list, false);
            enqueue(() -> backend.saveGraves(safe));
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
	public synchronized void upsertGrave(GravesConfig grave, boolean broken) {
		if (backend != null && isMySQL()) {
			GravesConfig snapshot = snapshot(grave);
            enqueue(() -> backend.upsertGrave(snapshot, broken));
		}
	}

/**
 * Update an existing grave row (no insert). Intended for state transitions such as removal.
 *
 * @param grave  config
 * @param broken broken flag
 */
public synchronized void updateExistingGrave(GravesConfig grave, boolean broken) {
	if (backend != null && isMySQL()) {
		GravesConfig snapshot = snapshot(grave);
        enqueue(() -> backend.updateExistingGrave(snapshot, broken));
	}
}



	/**
	 * Save broken graves from Grave objects.
	 *
	 * @param brokenGraves broken grave objects
	 */
	public synchronized void saveBrokenGravesFromObjects(List<Grave> brokenGraves) {
		List<GravesConfig> list = new ArrayList<GravesConfig>();
		if (brokenGraves != null) {
			for (Grave g : copy(brokenGraves)) {
				if (g != null && g.getGravesConfig() != null) {
					list.add(snapshot(g.getGravesConfig()));
				}
			}
		}
		if (backend != null) {
            if (restorationLoading) { retained = recoverySnapshot(); return; }
            // Compose before admission: later region resolution cannot erase a record
            // from a previously captured snapshot. Following removal snapshots win in order.
            List<GravesConfig> safe = preserving(list, true);
            enqueue(() -> backend.saveBrokenGraves(safe));
		}
	}

	/**
	 * Close backend.
	 */
	public synchronized void close() {
		if (backend != null) {
			enqueue(() -> backend.close());
		}
	}
    private static List<Grave> copy(List<Grave> graves) { synchronized (graves) { return new ArrayList<>(graves); } }
    public static GravesConfig snapshot(GravesConfig grave) {
        java.util.HashMap<Integer, org.bukkit.inventory.ItemStack> items = new java.util.HashMap<>();
        for (var entry : grave.getItems().entrySet()) items.put(entry.getKey(), entry.getValue() == null ? null : entry.getValue().clone());
        GravesConfig copy = new GravesConfig(grave.getUuid(), grave.getPlayerName(), grave.getLocation().clone(), items,
                grave.getExp(), grave.getDeathMessage(), grave.getTime(), grave.isDestroyed(), grave.getDestroyedTime(),
                grave.getDisplayUUID(), grave.getInteractUUID(), grave.getGraveDisplayType());
        copy.setWorldId(grave.getWorldId()); return copy;
    }
    /** Retain one latest authoritative snapshot when admission fails, rather than
     * discarding a death/removal transition. Capture and admission share this lock. */
    private synchronized void enqueue(Runnable write) {
        if (retained != null || recovering) { retained = recoverySnapshot(); return; }
        com.bencodez.gravestonesplus.graves.FeatureIO.Admission admitted = plugin.getFeatureIO().admit();
        if (admitted == null) { retained = recoverySnapshot(); return; }
        admitted.submit(() -> {
            try { write.run(); return true; }
            catch (RuntimeException failure) {
                synchronized (this) { if (retained == null) retained = recoverySnapshot(); }
                throw failure;
            }
        }).whenComplete((saved, failure) -> {
            if (failure != null) {
                synchronized (this) { if (retained == null) retained = recoverySnapshot(); }
                plugin.getLogger().severe("Grave backend write failed; latest grave snapshot is retained for storage recovery.");
            }
        });
    }
    private Recovery recoverySnapshot() {
        List<GravesConfig> active = new ArrayList<>(), broken = new ArrayList<>();
        for (Grave grave : plugin.activeGraveSnapshot()) active.add(snapshot(grave.getGravesConfig()));
        for (Grave grave : plugin.brokenGraveSnapshot()) broken.add(snapshot(grave.getGravesConfig()));
        return new Recovery(preserving(active, false), preserving(broken, true));
    }
    /** Runs only on the serial disk owner after older admitted work has drained. */
    private void flushRetained() {
        while (true) {
            Recovery recovery;
            synchronized (this) {
                if (restorationLoading) return;
                recovery = retained; retained = null; recovering = recovery != null;
                if (recovery != null) recovery = new Recovery(preserving(recovery.active(), false), preserving(recovery.broken(), true));
            }
            if (recovery == null) return;
            boolean failed = false;
            try {
                if (isMySQL()) {
                    for (GravesConfig grave : recovery.active()) backend.upsertGrave(grave, false);
                    for (GravesConfig grave : recovery.broken()) backend.upsertGrave(grave, true);
                } else {
                    backend.saveBrokenGraves(recovery.broken()); backend.saveGraves(recovery.active());
                }
            } catch (RuntimeException failure) {
                synchronized (this) { if (retained == null) retained = recovery; }
                failed = true;
                plugin.getLogger().severe("Retained grave backend snapshot could not be flushed; storage requires repair before shutdown.");
            } finally { synchronized (this) { recovering = false; } }
            // No busy retry on broken storage, and give queued feature commands priority.
            if (failed || plugin.getFeatureIO().hasQueuedWork()) return;
        }
    }
}
