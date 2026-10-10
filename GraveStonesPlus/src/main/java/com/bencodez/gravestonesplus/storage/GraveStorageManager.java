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
    private boolean closeRequested;
    private boolean backendClosed;
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

    public record RestorationRecords(List<GravesConfig> active, List<GravesConfig> broken) {}
    /** Keep JDBC on the serial I/O owner and Bukkit decoding/world lookup on the server owner. */
    public java.util.concurrent.CompletableFuture<RestorationRecords> loadMySQLForRestoration() {
        if (!(backend instanceof MySQLGraveStorageBackend sql))
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("MySQL backend unavailable"));
        return plugin.getFeatureIO().submit(() ->
                List.of(sql.loadDeferred(false), sql.loadDeferred(true))).thenCompose(raw -> {
            var result = new java.util.concurrent.CompletableFuture<RestorationRecords>();
            class Decode implements Runnable {
                final List<GravesConfig> active = new ArrayList<>(), broken = new ArrayList<>();
                int audience, cursor;
                void schedule() {
                    try { plugin.getBukkitScheduler().runTask(plugin, this); }
                    catch (RuntimeException failure) { result.completeExceptionally(failure); }
                }
                public void run() {
                    if (!plugin.isEnabled()) { result.completeExceptionally(new IllegalStateException("Plugin retired during restoration")); return; }
                    try {
                        // Fair owner admission: never decode an entire large SQL installation in one tick.
                        int budget = 128;
                        while (audience < raw.size() && budget > 0) {
                            var rows = raw.get(audience);
                            if (cursor == rows.size()) { audience++; cursor = 0; continue; }
                            (audience == 0 ? active : broken).add(rows.get(cursor++).get()); budget--;
                        }
                        if (audience == raw.size() || audience == 1 && cursor == raw.get(1).size())
                            result.complete(new RestorationRecords(List.copyOf(active), List.copyOf(broken)));
                        else schedule();
                    } catch (Throwable failure) { result.completeExceptionally(failure); }
                }
            }
            new Decode().schedule(); return result;
        });
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
		if (backend == null || backendClosed || closeRequested) return;
		// Closing is a terminal request, rather than an ordinary write admission.
		// FeatureIO invokes the drain listener after all admitted work has run,
		// including its final bounded shutdown drain when the queue is saturated.
		closeRequested = true;
		com.bencodez.gravestonesplus.graves.FeatureIO.Admission wake = plugin.getFeatureIO().admit();
		if (wake != null) wake.submit(() -> null);
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
        if (closeRequested || backendClosed) return;
        if (retained != null || recovering) { retained = recoverySnapshot(); return; }
        com.bencodez.gravestonesplus.graves.FeatureIO.Admission admitted = plugin.getFeatureIO().admit();
        if (admitted == null) { retained = recoverySnapshot(); return; }
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        admitted.submit(() -> {
            started.set(true);
            try { write.run(); return true; }
            catch (RuntimeException failure) {
                synchronized (this) { if (retained == null) retained = recoverySnapshot(); }
                throw failure;
            }
        }).whenComplete((saved, failure) -> {
            if (failure != null) {
                // Action failure already retained before FeatureIO's owner drain. Only
                // rejected execution needs a new snapshot; do not resurrect recovered work.
                if (!started.get()) synchronized (this) { if (!backendClosed && retained == null) retained = recoverySnapshot(); }
                plugin.getLogger().severe("Grave backend write failed; storage recovery was required.");
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
            boolean loading;
            synchronized (this) {
                if (backendClosed) return;
                loading = restorationLoading;
                recovery = loading ? null : retained;
                if (!loading) retained = null;
                recovering = recovery != null;
                if (recovery != null) recovery = new Recovery(preserving(recovery.active(), false), preserving(recovery.broken(), true));
            }
            if (loading) {
                // Shutdown during restoration must not publish an incomplete grave index.
                closeBackendIfRequested(true);
                return;
            }
            if (recovery == null) {
                closeBackendIfRequested();
                return;
            }
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
            if (failed || plugin.getFeatureIO().hasQueuedWork()) {
                if (failed) closeBackendIfRequested(true);
                return;
            }
        }
    }

    /** Called only by the FeatureIO serial owner after accepted work drains. */
    private void closeBackendIfRequested() { closeBackendIfRequested(false); }

    private void closeBackendIfRequested(boolean recoveryFailed) {
        GraveStorageBackend toClose;
        synchronized (this) {
            if (!closeRequested || backendClosed || (!recoveryFailed && retained != null) || recovering
                    || plugin.getFeatureIO().hasQueuedWork()) return;
            backendClosed = true;
            toClose = backend;
        }
        try {
            if (toClose != null) toClose.close();
        } catch (RuntimeException failure) {
            // The terminal close is intentionally at-most-once. The worker's
            // bounded shutdown must not keep retrying a backend that rejected it.
            plugin.getLogger().severe("Grave backend close failed after storage drain.");
        }
    }
}
