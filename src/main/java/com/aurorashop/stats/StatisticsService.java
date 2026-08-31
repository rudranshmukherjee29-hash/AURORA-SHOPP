package com.aurorashop.stats;

import com.aurorashop.config.ConfigManager;
import com.aurorashop.db.DatabaseManager;
import com.aurorashop.db.StatsAggregate;
import com.aurorashop.db.TransactionRecord;
import com.aurorashop.model.TransactionType;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Tracks shop-wide and per-item statistics. Successful and failed
 * transactions are both recorded (failures purely for admin diagnostics —
 * see design brief section 8) and persisted asynchronously so they never
 * add latency to the transaction path itself.
 */
public final class StatisticsService {

    private final JavaPlugin plugin;
    private final DatabaseManager database;
    private final ConfigManager config;

    private final AtomicLong totalBought = new AtomicLong();
    private final AtomicLong totalSold = new AtomicLong();
    private final AtomicLong totalTransactions = new AtomicLong();
    private final AtomicLong failedTransactions = new AtomicLong();
    private volatile BigDecimal totalSpent = BigDecimal.ZERO;
    private volatile BigDecimal totalEarned = BigDecimal.ZERO;
    private final Object moneyLock = new Object();

    private final Map<String, ItemStatEntry> perItem = new ConcurrentHashMap<>();

    public StatisticsService(JavaPlugin plugin, DatabaseManager database, ConfigManager config) {
        this.plugin = plugin;
        this.database = database;
        this.config = config;
    }

    public void initialize() {
        database.loadStatsAggregate().whenComplete((agg, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Could not load historical statistics; starting from zero.", error);
                return;
            }
            applyAggregate(agg);
        });
    }

    private void applyAggregate(StatsAggregate agg) {
        totalBought.set(agg.totalBought());
        totalSold.set(agg.totalSold());
        totalTransactions.set(agg.totalTransactions());
        failedTransactions.set(agg.failedTransactions());
        synchronized (moneyLock) {
            totalSpent = agg.totalSpent();
            totalEarned = agg.totalEarned();
        }
        for (Map.Entry<String, StatsAggregate.ItemAggregate> entry : agg.perItem().entrySet()) {
            StatsAggregate.ItemAggregate ia = entry.getValue();
            perItem.computeIfAbsent(entry.getKey(), k -> new ItemStatEntry())
                    .seed(ia.boughtCount(), ia.soldCount(), ia.spent(), ia.earned(), ia.transactionCount());
        }
    }

    public void recordSuccess(TransactionType type, String itemId, long quantity, BigDecimal amount,
                               UUID playerId, String playerName) {
        totalTransactions.incrementAndGet();
        ItemStatEntry entry = perItem.computeIfAbsent(itemId, k -> new ItemStatEntry());
        if (type == TransactionType.BUY) {
            totalBought.addAndGet(quantity);
            synchronized (moneyLock) {
                totalSpent = totalSpent.add(amount);
            }
            entry.recordBuy(quantity, amount);
        } else {
            totalSold.addAndGet(quantity);
            synchronized (moneyLock) {
                totalEarned = totalEarned.add(amount);
            }
            entry.recordSell(quantity, amount);
        }

        if (config.logTransactionsToConsole()) {
            plugin.getLogger().info(playerName + " " + type + " " + quantity + "x " + itemId + " for " + amount);
        }

        database.recordTransaction(new TransactionRecord(System.currentTimeMillis(), playerId, playerName,
                itemId, type.name(), quantity, amount, true, "SUCCESS"));
    }

    public void recordFailure(TransactionType type, String itemId, long quantity, UUID playerId, String playerName,
                               String resultStatus) {
        failedTransactions.incrementAndGet();
        if (config.logFailedTransactions()) {
            plugin.getLogger().info("Failed transaction: " + playerName + " " + type + " " + quantity + "x "
                    + itemId + " -> " + resultStatus);
        }
        database.recordTransaction(new TransactionRecord(System.currentTimeMillis(), playerId, playerName,
                itemId, type.name(), quantity, BigDecimal.ZERO, false, resultStatus));
    }

    public long totalBought() {
        return totalBought.get();
    }

    public long totalSold() {
        return totalSold.get();
    }

    public long totalTransactions() {
        return totalTransactions.get();
    }

    public long failedTransactions() {
        return failedTransactions.get();
    }

    public BigDecimal totalSpent() {
        synchronized (moneyLock) {
            return totalSpent;
        }
    }

    public BigDecimal totalEarned() {
        synchronized (moneyLock) {
            return totalEarned;
        }
    }

    public java.util.Optional<ItemStatEntry> itemStats(String itemId) {
        return java.util.Optional.ofNullable(perItem.get(itemId));
    }

    public List<Map.Entry<String, ItemStatEntry>> topBought(int limit) {
        return perItem.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, ItemStatEntry> e) -> e.getValue().boughtCount()).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    public List<Map.Entry<String, ItemStatEntry>> topSold(int limit) {
        return perItem.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, ItemStatEntry> e) -> e.getValue().soldCount()).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }
}
