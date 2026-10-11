package com.bencodez.gravestonesplus.graves;

import org.bukkit.Location;
import com.bencodez.gravestonesplus.GraveStonesPlus;

/** Internal creation handoff shared by death and administrative test graves.
 * A location callback may run before its caller finishes replacement preflight.
 * The admitted timer retries only that short preparation window; it performs
 * creation once and cancels itself. No second scheduler admission is required
 * after native drops are consumed. */
public final class GraveCreationTask implements Runnable {
    private static final int PREPARING = 0, ACCEPTED = 1, RUNNING = 2, FINISHED = 3;
    private final GraveStonesPlus plugin;
    private final Runnable creation;
    private final com.bencodez.gravestonesplus.storage.GravesConfig record;
    private final GraveRestoration restoration;
    private int priorGraves;
    private Grave replacement;
    private final java.util.concurrent.atomic.AtomicInteger phase = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicReference<com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask> task = new java.util.concurrent.atomic.AtomicReference<>();
    public GraveCreationTask(GraveStonesPlus plugin, Runnable creation) {
        this.plugin = plugin; this.creation = creation; this.record = null; this.restoration = null;
    }
    public GraveCreationTask(GraveStonesPlus plugin,
            com.bencodez.gravestonesplus.storage.GravesConfig record, java.util.function.Consumer<Grave> creation) {
        this.plugin = plugin; this.record = record; this.restoration = plugin.getGraveRestoration();
        this.creation = () -> {
            Grave grave = restoration == null ? new Grave(plugin, record) : restoration.creationGrave(record);
            creation.accept(grave);
            if (restoration != null) restoration.completeCreation(record);
        };
    }
    public int priorGraves() { return priorGraves; }
    public Grave replacement() { return replacement; }
    public void schedule(Location location) {
        try {
            if (record != null) {
                if (restoration == null) {
                    priorGraves = plugin.numberOfGraves(record.getUuid());
                    replacement = priorGraves >= plugin.getConfigFile().getGraveLimit() ? plugin.getOldestGrave(record.getUuid()) : null;
                } else {
                    var admission = restoration.reserveCreation(record);
                    priorGraves = admission.priorGraves(); replacement = admission.replacement();
                }
            }
            // The legacy location overload interpreted its delay in seconds.
            // Preserve that delay explicitly while obtaining a cancellable handle.
            var scheduled = plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtLocationTimer(
                    location, this, 2L, 1L, java.util.concurrent.TimeUnit.SECONDS);
            task.set(scheduled);
            if (scheduled == null || scheduled.isCancelled())
                throw new java.util.concurrent.RejectedExecutionException("Grave creation scheduler unavailable");
            if (phase.get() == FINISHED) cancel();
        } catch (RuntimeException rejected) { cancel(); throw rejected; }
    }
    public void accept() { phase.compareAndSet(PREPARING, ACCEPTED); }
    public void cancel() {
        phase.set(FINISHED);
        if (restoration != null) restoration.releaseCreation(record);
        var current = task.getAndSet(null);
        if (current != null) try { current.cancel(); }
        catch (RuntimeException unavailable) {
            // The phase fence still prevents any retired callback from creating a grave.
            // A platform cancellation failure must not skip kept-item rollback.
            task.compareAndSet(null, current);
            plugin.getLogger().fine("Grave creation task cancellation unavailable");
        }
    }
    @Override public void run() {
        if (phase.get() == FINISHED) { cancel(); return; }
        if (!phase.compareAndSet(ACCEPTED, RUNNING)) return;
        try { if (plugin.isEnabled()) creation.run(); }
        finally { cancel(); }
    }
}
