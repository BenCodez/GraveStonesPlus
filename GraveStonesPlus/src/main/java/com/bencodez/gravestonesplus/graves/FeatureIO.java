package com.bencodez.gravestonesplus.graves;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One bounded owner for selected feature disk work. Never uses the common pool. */
public final class FeatureIO implements AutoCloseable {
    private final ThreadPoolExecutor worker;
    private final Semaphore capacity;
    private volatile Thread owner;
    private final Object lifecycle = new Object();
    private int reservations;
    private boolean closing;
    private int unsettled, actors;
    private StoreGenerationLease generationLease;
    private volatile Throwable startupFailure;
    private final CompletableFuture<Boolean> ready = new CompletableFuture<>();
    private final CompletableFuture<Void> generationReleased = new CompletableFuture<>();
    private boolean finalizationRequested;
    private final ThreadPoolExecutor finalizer = new ThreadPoolExecutor(0, 1, 1L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "gsp-generation-finalizer"); thread.setDaemon(true); return thread;
            });
    public FeatureIO(java.nio.file.Path installation) { this(128, installation); }
    public FeatureIO(int capacity, java.nio.file.Path installation) {
        this(capacity, false);
        worker.execute(() -> {
            try { generationLease = StoreGenerationLease.acquire(installation); ready.complete(true); }
            catch (Throwable failure) {
                startupFailure = failure; ready.complete(false);
                // No selected initializer runs before ready; a failed acquisition owns no store resources.
                worker.shutdown(); generationReleased.complete(null); finalizer.shutdown();
            }
        });
    }
    /** Internal path binding for all selected persistence, including legacy Graves.yml. */
    public java.nio.file.Path storeRoot(java.nio.file.Path fallback) {
        if (startupFailure != null || !ready.getNow(false)) throw new IllegalStateException("Installation generation unavailable");
        return generationLease == null ? fallback : generationLease.root();
    }
    public boolean ownsInstallationLease() { return generationLease != null && startupFailure == null; }
    /** Initialization never reached the platform owner. Retire only on the disk owner, without blocking it. */
    public void abandonStartup() {
        synchronized (lifecycle) {
            if (closing || worker.isShutdown()) return;
            closing = true;
            worker.execute(() -> {
                synchronized (lifecycle) {
                    if (reservations != 0 || unsettled != 0 || actors != 0)
                        throw new IllegalStateException("Started generation cannot be abandoned");
                }
                try { if (generationLease != null) generationLease.close(); generationReleased.complete(null); }
                catch (Throwable failure) { generationReleased.completeExceptionally(failure); }
                finally { worker.shutdown(); finalizer.shutdown(); }
            });
        }
    }
    public CompletableFuture<Boolean> ready() { return ready.copy(); }
    public boolean isGenerationReleased() { return generationReleased.isDone() && !generationReleased.isCompletedExceptionally(); }
    /** Already charged to its journal entry. Only cancellation or proven recoverability may release it. */
    public Settlement retainUntilRecoverable() {
        synchronized (lifecycle) {
            if (closing || worker.isShutdown() || !ready.getNow(false)) return null;
            unsettled++; return new Settlement();
        }
    }
    public final class Settlement implements AutoCloseable {
        private final AtomicBoolean settled = new AtomicBoolean();
        @Override public void close() {
            if (settled.compareAndSet(false, true)) synchronized (lifecycle) { unsettled--; lifecycle.notifyAll(); }
        }
    }
    /** A bounded caller token outlives the native frame, including reentrant disable. */
    public Actor retainActor() {
        synchronized (lifecycle) {
            if (closing || worker.isShutdown() || !ready.getNow(false) || actors >= 128) return null;
            actors++; return new Actor();
        }
    }
    public final class Actor implements AutoCloseable {
        private final AtomicBoolean retired = new AtomicBoolean();
        @Override public void close() {
            if (retired.compareAndSet(false, true)) {
                synchronized (lifecycle) { actors--; lifecycle.notifyAll(); }
                finalizeGeneration();
            }
        }
    }
    private void finalizeGeneration() {
        synchronized (lifecycle) {
            if (!worker.isTerminated() || actors != 0 || finalizationRequested) return;
            finalizationRequested = true;
        }
        if (generationLease == null) { generationReleased.complete(null); finalizer.shutdown(); return; }
        finalizer.execute(() -> {
            try { if (generationLease != null) generationLease.close(); generationReleased.complete(null); }
            catch (Throwable failure) { generationReleased.completeExceptionally(failure); }
            finally { finalizer.shutdown(); }
        });
    }
    private final java.util.concurrent.atomic.AtomicReference<Runnable> urgentDrain = new java.util.concurrent.atomic.AtomicReference<>();
    private final AtomicBoolean drainRequested = new AtomicBoolean();
    private volatile Runnable drainListener = () -> { };
    public FeatureIO() { this(128); }
    public FeatureIO(int capacity) { this(capacity, false); ready.complete(true); }
    private FeatureIO(int capacity, boolean unused) {
        this.capacity = new Semaphore(capacity + 1);
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                // One physical slot per admission plus a coalesced internal drain wake,
                // even while the old frame releases its logical admission.
                new ArrayBlockingQueue<>(capacity + 2), r -> {
                    Thread t = new Thread(r, "gsp-feature-io"); t.setDaemon(false); owner = t; return t;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    /** Reserves publication capacity before a caller sends a non-replayable message. */
    public Admission admit() {
        synchronized (lifecycle) {
            if (closing || worker.isShutdown() || !capacity.tryAcquire()) return null;
            reservations++; return new Admission();
        }
    }
    public final class Admission implements AutoCloseable {
        private final AtomicBoolean used = new AtomicBoolean();
        public <T> CompletableFuture<T> submit(Supplier<T> action) {
            if (!used.compareAndSet(false, true)) return CompletableFuture.failedFuture(new RejectedExecutionException("admission already used"));
            CompletableFuture<T> result = new CompletableFuture<>();
            synchronized (lifecycle) {
            try { worker.execute(() -> {
                T value = null; Throwable failure = null;
                try {
                    if (startupFailure != null) throw new IllegalStateException("Installation generation unavailable", startupFailure);
                    if (generationLease != null) generationLease.check();
                    value = action.get();
                } catch (Throwable ex) { failure = ex; }
                capacity.release();
                // Recovery of an unrelated backend must not change this publication acknowledgment.
                try { notifyDrain(); } catch (RuntimeException ex) { java.util.logging.Logger.getLogger("GraveStonesPlus").severe("Backend recovery drain failed; the completed feature publication remains acknowledged."); }
                if (failure == null) result.complete(value); else result.completeExceptionally(failure);
            }); } catch (RejectedExecutionException failure) { capacity.release(); result.completeExceptionally(failure); }
            finally { reservations--; lifecycle.notifyAll(); }
            }
            return result;
        }
        @Override public void close() {
            if (used.compareAndSet(false, true)) {
                synchronized (lifecycle) { capacity.release(); reservations--; lifecycle.notifyAll(); }
                requestDrain();
            }
        }
    }
    public <T> CompletableFuture<T> submit(Supplier<T> action) {
        Admission admitted = admit();
        return admitted == null ? CompletableFuture.failedFuture(new RejectedExecutionException("feature worker unavailable")) : admitted.submit(action);
    }
    public CompletableFuture<Void> barrier() { return submit(() -> null); }
    /** One storage owner flushes its coalesced recovery snapshot after older work. */
    public void onDrain(Runnable listener) { drainListener = listener; }
    /** Coalesced internal wake does not consume a ticket reserved by a gameplay caller. */
    public void requestDrain() {
        synchronized (lifecycle) {
            if (worker.isShutdown() || !drainRequested.compareAndSet(false, true)) return;
            try { worker.execute(() -> { drainRequested.set(false); notifyDrain(); }); }
            catch (RejectedExecutionException full) {
                // Accepted queued work guarantees its final idle drain.
                drainRequested.set(false);
            }
        }
    }
    /** One storage owner may request bounded service even while ordinary feature work remains queued. */
    public void requestDrain(Runnable service) { urgentDrain.set(java.util.Objects.requireNonNull(service)); requestDrain(); }
    public boolean hasUnsubmittedAdmissions() { synchronized (lifecycle) { return reservations != 0; } }
    public boolean isOwnerThread() { return Thread.currentThread() == owner; }
    public boolean hasQueuedWork() { return !worker.getQueue().isEmpty(); }
    private void notifyDrain() {
        if (startupFailure != null) return;
        try { if (generationLease != null) generationLease.check(); }
        catch (java.io.IOException unavailable) { return; } // Retain charged ownership until the exact lease path is repaired.
        Runnable urgent = urgentDrain.getAndSet(null);
        if (urgent != null) urgent.run();
        if (!hasQueuedWork()) drainListener.run();
    }
    public boolean isTerminated() { return worker.isTerminated(); }
    @Override public void close() {
        if (Thread.currentThread() == owner) throw new IllegalStateException("Disk owner cannot wait for its own shutdown");
        boolean interrupted = false;
        synchronized (lifecycle) {
            closing = true;
            // A ticket obtained before sending must remain publishable during shutdown.
            while (reservations > 0 || unsettled > 0) {
                requestDrain();
                try { lifecycle.wait(250L); } catch (InterruptedException failure) { interrupted = true; }
            }
            // If full, the last admitted task performs the same final drain pass.
            try { worker.execute(this::notifyDrain); } catch (RejectedExecutionException alreadyFullOrClosed) { }
            worker.shutdown();
        }
        try {
            // Returning is the shutdown completion boundary. Do not discard accepted
            // writes after an arbitrary timeout; slow storage makes shutdown wait.
            while (!worker.isTerminated()) {
                try { worker.awaitTermination(2, TimeUnit.SECONDS); }
                catch (InterruptedException failure) { interrupted = true; }
            }
        } finally {
            finalizeGeneration();
            boolean wait;
            synchronized (lifecycle) { wait = actors == 0; }
            if (wait) {
                while (!generationReleased.isDone()) {
                    try { generationReleased.get(250L, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException failure) { interrupted = true; }
                    catch (TimeoutException waiting) { }
                    catch (ExecutionException failure) { throw new IllegalStateException("Installation lease retirement failed", failure.getCause()); }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
