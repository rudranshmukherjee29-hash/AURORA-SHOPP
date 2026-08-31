package com.aurorashop.stock;

import com.aurorashop.config.ConfigManager;
import com.aurorashop.db.DatabaseManager;
import com.aurorashop.model.ShopItem;
import com.aurorashop.shop.ShopRegistry;
import com.aurorashop.util.SchedulerUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Tracks two kinds of live, mutable shop state that intentionally do NOT
 * live on the immutable {@link ShopItem} snapshot: current stock levels
 * for stock-limited items, and per-player daily buy/sell usage.
 * <p>
 * Stock reservation is lock-free: {@link #tryReserveStock} uses a
 * compare-and-set retry loop on an {@link AtomicInteger}, which is both
 * correct under concurrent access from multiple players and cheap — no
 * thread ever blocks waiting for another player's purchase to finish.
 * Daily-limit counters use a per-key monitor instead, since each update
 * touches two fields (the day bucket and the count) that must change
 * together.
 */
public final class StockService {

    private final JavaPlugin plugin;
    private final ShopRegistry registry;
    private final ConfigManager config;
    private final DatabaseManager database;

    private final Map<String, AtomicInteger> currentStock = new ConcurrentHashMap<>();
    private final Map<String, DailyUsage> dailyUsage = new ConcurrentHashMap<>();

    public StockService(JavaPlugin plugin, ShopRegistry registry, ConfigManager config, DatabaseManager database) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
        this.database = database;
    }

    /**
     * Ensures every currently-configured stock-limited item has an
     * in-memory counter, defaulting to its configured initial value.
     * Uses {@code computeIfAbsent}, so it NEVER overwrites an item that
     * already has in-memory state — this is what makes it safe to call
     * again on {@code /shopadmin reload}. Reload must never re-derive an
     * existing item's live stock count from anywhere, config included:
     * only {@link #loadPersistedState()} (startup-only, see below) is
     * allowed to set an existing item's counter.
     */
    public void initializeStockFromConfig() {
        for (ShopItem item : registry.allItems()) {
            item.stockConfig().ifPresent(stockConfig ->
                    currentStock.computeIfAbsent(item.id(), id -> new AtomicInteger(stockConfig.initialCurrent())));
        }
    }

    /**
     * Loads persisted stock levels and today's daily usage from the
     * database, OVERWRITING whatever is currently in memory for any key
     * found. This is intentionally startup-only: calling it after the
     * server has been running (e.g. from {@code /shopadmin reload}) would
     * roll live counters back to their last periodic-flush snapshot —
     * up to 5 minutes stale — which for a stock counter is a genuine dupe
     * window (buy the last unit, reload, stock reappears) rather than a
     * cosmetic inconsistency. AuroraShopPlugin calls this exactly once,
     * during {@code onEnable}.
     */
    public void loadPersistedState() {
        database.loadStockSnapshot().whenComplete((snapshot, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not load persisted stock levels; using configured defaults.", error);
                return;
            }
            for (Map.Entry<String, Integer> entry : snapshot.entrySet()) {
                registry.item(entry.getKey()).flatMap(ShopItem::stockConfig).ifPresent(stockConfig -> {
                    int clamped = Math.max(0, Math.min(stockConfig.max(), entry.getValue()));
                    currentStock.put(entry.getKey(), new AtomicInteger(clamped));
                });
            }
        });

        long dayEpoch = currentShopDayEpoch();
        database.loadDailyUsageSnapshot(dayEpoch).whenComplete((snapshot, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not load persisted daily usage; starting fresh.", error);
                return;
            }
            for (Map.Entry<String, Integer> entry : snapshot.entrySet()) {
                DailyUsage usage = dailyUsage.computeIfAbsent(entry.getKey(), k -> new DailyUsage());
                synchronized (usage) {
                    usage.dayEpoch = dayEpoch;
                    usage.count = entry.getValue();
                }
            }
        });
    }

    /** Attempts to atomically decrement stock by {@code amount}. Returns false if insufficient (never goes negative). */
    public boolean tryReserveStock(String itemId, long amount) {
        AtomicInteger stock = currentStock.get(itemId);
        if (stock == null) {
            return true; // unlimited stock
        }
        int amt = Math.toIntExact(amount);
        while (true) {
            int current = stock.get();
            if (current < amt) {
                return false;
            }
            if (stock.compareAndSet(current, current - amt)) {
                return true;
            }
            // CAS failed because another thread changed it concurrently — retry against the fresh value.
        }
    }

    /** Reverses a successful reservation (used to roll back a transaction that failed after stock was reserved). */
    public void releaseStock(String itemId, long amount) {
        AtomicInteger stock = currentStock.get(itemId);
        if (stock == null) {
            return;
        }
        registry.item(itemId).flatMap(ShopItem::stockConfig).ifPresentOrElse(
                cfg -> stock.updateAndGet(v -> Math.min(cfg.max(), v + Math.toIntExact(amount))),
                () -> stock.addAndGet(Math.toIntExact(amount))
        );
    }

    /** Adds stock back up on a sale, if config.yml's restockOnSell is enabled. Capped at the item's max. */
    public void restockOnSell(String itemId, long amount) {
        if (!config.restockOnSell()) {
            return;
        }
        registry.item(itemId).flatMap(ShopItem::stockConfig).ifPresent(cfg -> {
            AtomicInteger stock = currentStock.computeIfAbsent(itemId, id -> new AtomicInteger(0));
            stock.updateAndGet(v -> Math.min(cfg.max(), v + Math.toIntExact(amount)));
        });
    }

    public java.util.Optional<Integer> currentStock(String itemId) {
        AtomicInteger stock = currentStock.get(itemId);
        return stock == null ? java.util.Optional.empty() : java.util.Optional.of(stock.get());
    }

    public void restockToMax(String itemId) {
        registry.item(itemId).flatMap(ShopItem::stockConfig).ifPresent(cfg ->
                currentStock.computeIfAbsent(itemId, id -> new AtomicInteger(0)).set(cfg.max()));
    }

    public void restockBy(String itemId, int amount) {
        registry.item(itemId).flatMap(ShopItem::stockConfig).ifPresent(cfg ->
                currentStock.computeIfAbsent(itemId, id -> new AtomicInteger(0))
                        .updateAndGet(v -> Math.min(cfg.max(), v + amount)));
    }

    /**
     * Attempts to atomically consume {@code amount} against a player's daily
     * limit for {@code itemId}/{@code action}. Automatically rolls the
     * counter over to zero if the stored bucket is from a previous shop
     * day. Returns false (without consuming anything) if the limit would
     * be exceeded.
     */
    public boolean tryConsumeDailyLimit(UUID playerId, String itemId, boolean isBuy, long amount, int limit) {
        String key = compositeKey(playerId, itemId, isBuy);
        DailyUsage usage = dailyUsage.computeIfAbsent(key, k -> new DailyUsage());
        long today = currentShopDayEpoch();
        synchronized (usage) {
            if (usage.dayEpoch != today) {
                usage.dayEpoch = today;
                usage.count = 0;
            }
            if (usage.count + amount > limit) {
                return false;
            }
            usage.count += (int) amount;
            return true;
        }
    }

    public void releaseDailyLimit(UUID playerId, String itemId, boolean isBuy, long amount) {
        String key = compositeKey(playerId, itemId, isBuy);
        DailyUsage usage = dailyUsage.get(key);
        if (usage == null) {
            return;
        }
        synchronized (usage) {
            usage.count = Math.max(0, usage.count - (int) amount);
        }
    }

    public int dailyUsageUsed(UUID playerId, String itemId, boolean isBuy) {
        DailyUsage usage = dailyUsage.get(compositeKey(playerId, itemId, isBuy));
        if (usage == null) {
            return 0;
        }
        synchronized (usage) {
            return usage.dayEpoch == currentShopDayEpoch() ? usage.count : 0;
        }
    }

    private String compositeKey(UUID playerId, String itemId, boolean isBuy) {
        return playerId + ":" + itemId + ":" + (isBuy ? "B" : "S");
    }

    private long currentShopDayEpoch() {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        LocalDateTime shifted = now.minusHours(config.dailyResetHour());
        return shifted.toLocalDate().toEpochDay();
    }

    /** Persists current stock + today's daily usage. Safe to call periodically and on shutdown. */
    public java.util.concurrent.CompletableFuture<Void> flush() {
        Map<String, Integer> stockSnapshot = new java.util.HashMap<>();
        currentStock.forEach((id, atomic) -> stockSnapshot.put(id, atomic.get()));
        java.util.concurrent.CompletableFuture<Void> stockFuture = stockSnapshot.isEmpty()
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : database.saveStockSnapshot(stockSnapshot);

        long today = currentShopDayEpoch();
        Map<String, Integer> usageSnapshot = new java.util.HashMap<>();
        dailyUsage.forEach((key, usage) -> {
            synchronized (usage) {
                if (usage.dayEpoch == today) {
                    usageSnapshot.put(key, usage.count);
                }
            }
        });
        java.util.concurrent.CompletableFuture<Void> usageFuture = usageSnapshot.isEmpty()
                ? java.util.concurrent.CompletableFuture.completedFuture(null)
                : database.saveDailyUsageSnapshot(today, usageSnapshot);

        return java.util.concurrent.CompletableFuture.allOf(stockFuture, usageFuture);
    }

    public void startPeriodicFlush() {
        SchedulerUtil.runAsyncRepeating(plugin, this::flush, 20L * 60 * 5, 20L * 60 * 5); // every 5 minutes
    }

    public void runAutomaticRestock() {
        if (!config.autoRestockEnabled()) {
            return;
        }
        for (ShopItem item : registry.allItems()) {
            item.stockConfig().ifPresent(cfg -> {
                if (!cfg.automaticRestock()) {
                    return;
                }
                if (config.restockRefillToMax()) {
                    restockToMax(item.id());
                } else {
                    restockBy(item.id(), 1);
                }
            });
        }
    }

    private static final class DailyUsage {
        long dayEpoch = -1;
        int count = 0;
    }
}
