package com.bencodez.gravestonesplus.graves;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import com.bencodez.gravestonesplus.GraveStonesPlus;

/** Delivery acknowledgment first, then durable suppression. No offline marks. */
public final class GraveReminderStore {
    public record Notice(String key, long deadline, String description) { }
    private final DurableFeatureState state;
    private final java.util.function.Consumer<String> warning;
    private final Map<String, Long> delivered = new HashMap<>();
    public GraveReminderStore(GraveStonesPlus plugin) {
        this(new DurableFeatureState(plugin.getFeatureIO(), new java.io.File(plugin.getDataFolder(), "grave-reminders.yml").toPath(), 8192), message -> plugin.getLogger().warning(message));
    }
    public GraveReminderStore(DurableFeatureState state) { this(state, message -> { }); }
    public GraveReminderStore(DurableFeatureState state, java.util.function.Consumer<String> warning) { this.state = state; this.warning = warning; }
    public CompletableFuture<Boolean> loaded() { return state.loaded(); }
    public boolean isReady() { return state.isReady(); }
    /** Must be called on the recipient entity owner. Sender returns true only after send. */
    public synchronized List<Notice> deliver(List<Notice> candidates, long now,
            java.util.function.Predicate<List<Notice>> sender) {
        if (!state.isReady()) return List.of();
        delivered.entrySet().removeIf(entry -> entry.getValue() <= now);
        List<Notice> fresh = candidates.stream().filter(n -> n.deadline() > now
                && !state.snapshot().containsKey(n.key()) && !delivered.containsKey(n.key())).distinct().limit(64).toList();
        if (fresh.isEmpty() || delivered.size() + fresh.size() > 8192) return List.of();
        Set<String> retained = new HashSet<>(delivered.keySet());
        state.snapshot().keySet().stream().filter(key -> deadlineOf(key) > now).forEach(retained::add);
        for (Notice notice : fresh) retained.add(notice.key());
        if (retained.size() > 8192) return List.of();
        FeatureIO.Admission admitted = state.admit();
        if (admitted == null) return List.of();
        try {
            if (!sender.test(fresh)) { admitted.close(); return List.of(); }
        } catch (RuntimeException failure) { admitted.close(); throw failure; }
        for (Notice notice : fresh) delivered.put(notice.key(), notice.deadline());
        state.update(admitted, next -> {
            next.keySet().removeIf(key -> deadlineOf(key) <= now);
            for (Notice notice : fresh) next.put(notice.key(), "sent");
            return true;
        }).whenComplete((saved, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(saved)) warning.accept("Grave reminder was delivered but suppression could not be saved; local replay is suppressed, restart may repeat it. Check feature storage.");
        });
        return fresh;
    }
    public static long deadlineOf(String key) {
        try { return Long.parseLong(key.substring(key.lastIndexOf('/') + 1)); }
        catch (RuntimeException invalid) { return Long.MAX_VALUE; }
    }
    // Legacy entry point must not mark before a delivery acknowledgment.
    @Deprecated public boolean markIfNew(UUID player, UUID owner, long time, String kind, long deadline) { return false; }
    @Deprecated public CompletableFuture<Boolean> markIfNewAsync(UUID player, UUID owner, long time, String kind, long deadline) { return CompletableFuture.completedFuture(false); }
    public void close() { /* shared FeatureIO owns lifecycle */ }
}
