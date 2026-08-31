package com.aurorashop.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around Paper's region-aware schedulers so the rest of the
 * plugin never has to think about Folia vs. a normal Paper server.
 * <p>
 * On regular Paper, {@code Bukkit.getGlobalRegionScheduler()},
 * {@code Bukkit.getAsyncScheduler()}, and {@code Entity#getScheduler()}
 * (all part of the standard Paper API, not a Folia-only addition) simply
 * run on the main thread / a background thread as appropriate. On Folia
 * they dispatch to the correct region or entity-owning thread. Using these
 * everywhere means AuroraShop needs no Folia-specific branches at all.
 */
public final class SchedulerUtil {

    private SchedulerUtil() {
    }

    /** Runs a task that must touch global/world state, on the correct thread for that. */
    public static void runGlobal(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
    }

    /** Runs a task bound to a specific entity (e.g. giving that player items), on its owning thread. */
    public static void runAtEntity(Plugin plugin, Entity entity, Runnable task) {
        entity.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    /** Runs a task on a background thread pool. Never touch Bukkit API state from here. */
    public static void runAsync(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }

    /** Runs a repeating background task. Never touch Bukkit API state from here. */
    public static void runAsyncRepeating(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        long initialMs = initialDelayTicks * 50L;
        long periodMs = periodTicks * 50L;
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, ignored -> task.run(),
                Math.max(1, initialMs), Math.max(50, periodMs), TimeUnit.MILLISECONDS);
    }
}
