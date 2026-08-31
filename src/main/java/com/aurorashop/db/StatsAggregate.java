package com.aurorashop.db;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Snapshot of aggregate statistics computed from the {@code transactions}
 * table, used to rehydrate {@code StatisticsService}'s in-memory counters
 * when the plugin starts so stats survive a restart.
 */
public record StatsAggregate(
        long totalBought,
        long totalSold,
        BigDecimal totalSpent,
        BigDecimal totalEarned,
        long totalTransactions,
        long failedTransactions,
        Map<String, ItemAggregate> perItem
) {
    public record ItemAggregate(
            long boughtCount,
            long soldCount,
            BigDecimal spent,
            BigDecimal earned,
            long transactionCount
    ) {
    }
}
