package com.bencodez.gravestonesplus.tests.graves;

import com.bencodez.gravestonesplus.GraveStonesPlus;
import com.bencodez.gravestonesplus.graves.*;
import java.lang.reflect.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.mockito.MockedStatic;

/** Test-only bridge to the existing package-private gameplay fixture. */
final class ClaimHarness implements AutoCloseable {
    private final Object delegate;
    final com.bencodez.gravestonesplus.config.Config config;
    final com.bencodez.simpleapi.scheduler.BukkitScheduler scheduler;
    final com.bencodez.simpleapi.folialib.FoliaLib folia;
    final com.bencodez.simpleapi.folialib.impl.ServerImplementation platform;
    final ItemStack stored;
    final Queue<Runnable> ownerWork;
    final FeatureIO io;
    final TrustedGraveAccessStore access;
    final GraveClaimLedger ledger;
    final GraveStonesPlus plugin;
    final Player owner;
    final PlayerInventory ownerInventory;
    final PluginManager events;
    final Queue<Runnable> entityWork;
    final Queue<Runnable> regionWork;
    final List<Grave> active;
    final MockedStatic<Bukkit> bukkit;
    final Grave grave;
    final ItemStack cloned;
    final org.bukkit.World world;

    ClaimHarness() throws Exception {
        var type = Class.forName("com.bencodez.gravestonesplus.graves.GraveActionsTest$Harness");
        var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        delegate = constructor.newInstance();
        config = field(delegate, "config"); scheduler = field(delegate, "scheduler");
        folia = field(delegate, "folia"); platform = field(delegate, "platform");
        stored = field(delegate, "stored"); ownerWork = field(delegate, "ownerWork");
        io = field(delegate, "io"); access = field(delegate, "access"); ledger = field(delegate, "ledger");
        plugin = field(delegate, "plugin"); owner = field(delegate, "owner");
        ownerInventory = field(delegate, "ownerInventory"); events = field(delegate, "events");
        entityWork = field(delegate, "entityWork"); regionWork = field(delegate, "regionWork"); active = field(delegate, "active");
        world = field(delegate, "world");
        bukkit = field(delegate, "bukkit"); grave = field(delegate, "grave"); cloned = field(delegate, "cloned");
    }
    static void pluginField(GraveStonesPlus plugin, String name, Object value) throws Exception {
        var field = GraveStonesPlus.class.getDeclaredField(name); field.setAccessible(true); field.set(plugin, value);
    }
    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws ReflectiveOperationException {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(owner);
    }
    private static Object invoke(Object owner, String name) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true);
        try { return method.invoke(owner); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw failure;
        }
    }
    private Object fixture(String name) throws Exception {
        var method=delegate.getClass().getEnclosingClass().getDeclaredMethod(name,delegate.getClass());method.setAccessible(true);
        try { return method.invoke(null,delegate); }
        catch(InvocationTargetException failure) { if(failure.getCause() instanceof Exception cause) throw cause;throw failure; }
    }
    GraveRestoration limitRestoration() throws Exception { return (GraveRestoration)fixture("limitRestoration"); }
    org.bukkit.entity.ExperienceOrb groundWorld() throws Exception { return (org.bukkit.entity.ExperienceOrb)fixture("groundWorld"); }
    void drainGround() throws Exception { fixture("drainGround"); }
    void resumeLimit() throws Exception { invoke(grave,"resumeLimitPayout"); }
    GraveClaimLedger restartLedger() throws Exception {
        return new GraveClaimLedger(new DurableFeatureState(io,(DurableFeatureState.Disk)field(delegate,"claimDisk"),8192));
    }
    String identity() throws Exception { return (String) invoke(delegate, "identity"); }
    void drain() throws Exception { invoke(delegate, "drain"); }
    void retireAcknowledgments() throws Exception { invoke(grave, "retireAcknowledgments"); }
    void failClaimDisk() throws Exception {
        Object disk = field(delegate, "claimDisk");
        var fail = disk.getClass().getDeclaredField("fail"); fail.setAccessible(true); fail.setBoolean(disk, true);
    }
    static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(3, TimeUnit.SECONDS); }
    @Override public void close() throws Exception { invoke(delegate, "close"); }
}
