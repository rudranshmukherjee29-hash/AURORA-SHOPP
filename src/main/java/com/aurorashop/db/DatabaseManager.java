package com.aurorashop.db;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Persistence abstraction implemented by {@code SQLiteDatabaseManager}
 * (default) and {@code MySQLDatabaseManager} (opt-in via config.yml).
 * <p>
 * Every method that touches the database is asynchronous and returns a
 * {@link CompletableFuture} — nothing here may ever be called from the
 * main/region thread and blocked on, or a slow disk/network round trip
 * would stall the server. Callers that need to act on the result once it
 * arrives must hop back to the appropriate scheduler themselves (see
 * {@code SchedulerUtil}).
 */
public interface DatabaseManager {

    void initialize() throws Exception;

    void shutdown();

    CompletableFuture<Void> recordTransaction(TransactionRecord record);

    CompletableFuture<Map<String, Integer>> loadStockSnapshot();

    CompletableFuture<Void> saveStockSnapshot(Map<String, Integer> currentStockByItemId);

    CompletableFuture<Map<String, Integer>> loadDailyUsageSnapshot(long dayEpoch);

    CompletableFuture<Void> saveDailyUsageSnapshot(long dayEpoch, Map<String, Integer> countByCompositeKey);

    CompletableFuture<StatsAggregate> loadStatsAggregate();
}
