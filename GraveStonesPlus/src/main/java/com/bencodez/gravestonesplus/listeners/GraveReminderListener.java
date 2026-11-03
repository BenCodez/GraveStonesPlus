package com.bencodez.gravestonesplus.listeners;

import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.*;

/** Bounded round-robin scheduling. All recipient reads and messages use entity ownership. */
public final class GraveReminderListener implements Listener {
    private final GraveStonesPlus plugin;
    private volatile boolean running;
    private volatile long generation;
    private int cursor;
    private com.bencodez.simpleapi.folialib.wrapper.task.WrappedTask task;
    private final Map<UUID, Integer> graveCursors = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentMap<UUID, Object> scheduled = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int LOGIN_PENDING = Integer.MIN_VALUE;
    public GraveReminderListener(GraveStonesPlus plugin) { this.plugin = plugin; }
    public void start() {
        running = true; long expected = ++generation;
        task = plugin.getBukkitScheduler().getFoliaLib().getImpl().runTimer(() -> {
            if (!running || generation != expected) return;
            List<? extends Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (online.isEmpty()) return;
            int count = Math.min(16, online.size());
            for (int i = 0; i < count; i++) {
                Player player = online.get(Math.floorMod(cursor++, online.size()));
                schedule(player, expected);
            }
        }, 20L, 100L);
    }
    public void stop() { if (task != null) { task.cancel(); task = null; } running = false; generation++; scheduled.clear(); graveCursors.clear(); }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (graveCursors.size() < 4096 || graveCursors.containsKey(id)) graveCursors.put(id, LOGIN_PENDING);
        schedule(event.getPlayer(), generation);
    }
    private void schedule(Player player, long expected) {
        UUID id = player.getUniqueId();
        Object admission = new Object();
        synchronized (scheduled) {
            if (!running || generation != expected || scheduled.size() >= 128
                    || scheduled.putIfAbsent(id, admission) != null) return;
        }
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable release = () -> scheduled.remove(id, admission);
        Runnable retired = () -> { if (started.compareAndSet(false, true)) release.run(); };
        try {
            plugin.getBukkitScheduler().getFoliaLib().getImpl().runAtEntityWithFallback(player, ignored -> {
                if (!started.compareAndSet(false, true)) return;
                try {
                    if (running && generation == expected && scheduled.get(id) == admission && player.isOnline())
                        checkPlayer(player);
                } finally { release.run(); }
            }, retired).whenComplete((result, failure) -> {
                if (failure != null || result != com.bencodez.simpleapi.folialib.enums.EntityTaskResult.SUCCESS)
                    retired.run();
            });
        } catch (RuntimeException rejected) { retired.run(); }
    }
        @EventHandler public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        scheduled.remove(event.getPlayer().getUniqueId()); graveCursors.remove(event.getPlayer().getUniqueId());
        plugin.getGraveNavigation().forget(event.getPlayer());
    }
    public void checkPlayer(Player player) {
        if (!running || !player.isOnline()) return;
        // Navigation has its own grave/access readiness guards. A failed
        // reminder file must not prevent bounded cleanup of stale compasses.
        plugin.getGraveNavigation().refresh(player);
        if (!readyForLoginSummary()) return;
        boolean loginSummary = graveCursors.getOrDefault(player.getUniqueId(), 0) == LOGIN_PENDING;
        long now = System.currentTimeMillis();
        List<GraveReminderStore.Notice> notices = new ArrayList<>();
        List<Grave> owned = plugin.getGraves(player);
        int start = graveCursors.getOrDefault(player.getUniqueId(), 0);
        if (start == LOGIN_PENDING) start = 0;
        int examined = Math.min(256, owned.size());
        for (int i = 0; i < examined; i++) {
            Grave grave = owned.get(Math.floorMod(start + i, owned.size()));
            if (!plugin.isLiveGrave(grave)) continue;
            long expiry = grave.getExpiryDeadline();
            long protection = plugin.getConfigFile().isBreakOtherGravesEnabled()
                    ? GraveDeadlines.deadline(grave.getGravesConfig().getTime(), plugin.getConfigFile().getBreakOtherGravesTimeBeforeBreakable().getMillis()) : 0;
            add(notices, player, grave, "expiry", expiry, plugin.getConfigFile().getExpiryReminderMillis(), now);
            add(notices, player, grave, "protection", protection, plugin.getConfigFile().getProtectionReminderMillis(), now);
        }
        List<GraveReminderStore.Notice> sent = plugin.getGraveReminderStore().deliver(notices, now, fresh -> {
            if (!running || !player.isOnline()) return false;
            String details = fresh.stream().limit(3).map(GraveReminderStore.Notice::description).collect(java.util.stream.Collectors.joining("; "));
            player.sendMessage("\u00a7eGraves: " + details + (fresh.size() > 3 ? "; +" + (fresh.size() - 3) + " deadlines" : "") + ". /gravestonesplus graves");
            return true;
        });
        if (loginSummary && sent.isEmpty() && player.isOnline()) {
            List<Grave> accessible = plugin.getAccessibleGraves(player);
            if (!accessible.isEmpty()) player.sendMessage("\u00a7eYou have " + accessible.size() + " accessible grave(s). "
                    + accessible.get(0).getNavigationMessage(player) + ". /gravestonesplus graves");
        }
        // Publish the cursor only after delivery/summary completes. A failed send retains the login retry.
        if (graveCursors.size() < 4096 || graveCursors.containsKey(player.getUniqueId()))
            graveCursors.put(player.getUniqueId(), start + examined);
    }
    private boolean readyForLoginSummary() {
        var restoration = plugin.getGraveRestoration();
        return plugin.getGraveReminderStore().isReady()
                && plugin.getTrustedGraveAccessStore().isReady()
                && plugin.getGraveClaimLedger().isReady()
                && (restoration == null || !restoration.isLoading());
    }
    private void add(List<GraveReminderStore.Notice> notices, Player player, Grave grave, String kind, long deadline, long threshold, long now) {
        if (!GraveDeadlines.eligible(now, deadline, threshold)) return;
        String key = player.getUniqueId() + "/" + GraveIdentity.of(grave.getGravesConfig()) + "/" + kind + "/" + deadline;
        String label = kind.equals("expiry") ? "expires" : "protection ends";
        notices.add(new GraveReminderStore.Notice(key, deadline,
                grave.getLocationDescription() + " " + label + " in " + Math.max(1, (deadline - now + 59_999) / 60_000) + "m"));
    }
}
