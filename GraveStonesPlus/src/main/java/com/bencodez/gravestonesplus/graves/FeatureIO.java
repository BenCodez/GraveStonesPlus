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
    private volatile Runnable drainListener = () -> { };
    public FeatureIO() { this(128); }
    public FeatureIO(int capacity) {
        this.capacity = new Semaphore(capacity + 1);
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                // One physical queue slot per admission, even while the old worker
                // frame finishes a drain after releasing its logical admission.
                new ArrayBlockingQueue<>(capacity + 1), r -> {
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
                try { value = action.get(); } catch (Throwable ex) { failure = ex; }
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
            if (used.compareAndSet(false, true)) synchronized (lifecycle) { capacity.release(); reservations--; lifecycle.notifyAll(); }
        }
    }
    public <T> CompletableFuture<T> submit(Supplier<T> action) {
        Admission admitted = admit();
        return admitted == null ? CompletableFuture.failedFuture(new RejectedExecutionException("feature worker unavailable")) : admitted.submit(action);
    }
    public CompletableFuture<Void> barrier() { return submit(() -> null); }
    /** One storage owner flushes its coalesced recovery snapshot after older work. */
    public void onDrain(Runnable listener) { drainListener = listener; }
    public boolean hasQueuedWork() { return !worker.getQueue().isEmpty(); }
    private void notifyDrain() { if (!hasQueuedWork()) drainListener.run(); }
    public boolean isTerminated() { return worker.isTerminated(); }
    @Override public void close() {
        if (Thread.currentThread() == owner) throw new IllegalStateException("Disk owner cannot wait for its own shutdown");
        boolean interrupted = false;
        synchronized (lifecycle) {
            closing = true;
            // A ticket obtained before sending must remain publishable during shutdown.
            while (reservations > 0) {
                try { lifecycle.wait(); } catch (InterruptedException failure) { interrupted = true; }
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
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
