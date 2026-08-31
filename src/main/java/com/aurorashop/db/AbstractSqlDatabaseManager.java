package com.aurorashop.db;

import com.aurorashop.util.SchedulerUtil;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Shared implementation for both backends: connection pooling via
 * HikariCP, table setup, and every query. Subclasses only supply the
 * dialect-specific {@link HikariConfig} and the auto-increment column
 * syntax, since that's the one place SQLite and MySQL DDL genuinely
 * differ for our schema.
 * <p>
 * Every public method immediately hands off to a background thread via
 * {@code SchedulerUtil.runAsync} — nothing here ever blocks the caller's
 * thread, which matters because callers are frequently on the main/region
 * thread (e.g. finishing up after a transaction).
 */
public abstract class AbstractSqlDatabaseManager implements DatabaseManager {

    protected final JavaPlugin plugin;
    protected HikariDataSource dataSource;

    protected AbstractSqlDatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    protected abstract HikariConfig buildHikariConfig();

    /** e.g. "INTEGER PRIMARY KEY AUTOINCREMENT" for SQLite vs "BIGINT AUTO_INCREMENT PRIMARY KEY" for MySQL. */
    protected abstract String autoIncrementPrimaryKeyType();

    @Override
    public void initialize() throws Exception {
        dataSource = new HikariDataSource(buildHikariConfig());
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS aurorashop_transactions (
                        id %s,
                        timestamp_millis BIGINT NOT NULL,
                        player_id VARCHAR(36) NOT NULL,
                        player_name VARCHAR(32) NOT NULL,
                        item_id VARCHAR(64) NOT NULL,
                        type VARCHAR(8) NOT NULL,
                        quantity BIGINT NOT NULL,
                        amount DECIMAL(18,2) NOT NULL,
                        success BOOLEAN NOT NULL,
                        result_status VARCHAR(32) NOT NULL
                    )
                    """.formatted(autoIncrementPrimaryKeyType()));
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS aurorashop_stock_state (
                        item_id VARCHAR(64) PRIMARY KEY,
                        current_stock INTEGER NOT NULL
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS aurorashop_daily_usage (
                        day_epoch BIGINT NOT NULL,
                        composite_key VARCHAR(160) NOT NULL,
                        count INTEGER NOT NULL,
                        PRIMARY KEY (day_epoch, composite_key)
                    )
                    """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_aurorashop_tx_item ON aurorashop_transactions(item_id)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_aurorashop_tx_time ON aurorashop_transactions(timestamp_millis)");
        }
        plugin.getLogger().info("AuroraShop database ready (" + getClass().getSimpleName() + ").");
    }

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @Override
    public CompletableFuture<Void> recordTransaction(TransactionRecord record) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        SchedulerUtil.runAsync(plugin, () -> {
            String sql = "INSERT INTO aurorashop_transactions "
                    + "(timestamp_millis, player_id, player_name, item_id, type, quantity, amount, success, result_status) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, record.timestampMillis());
                ps.setString(2, record.playerId().toString());
                ps.setString(3, record.playerName());
                ps.setString(4, record.itemId());
                ps.setString(5, record.type());
                ps.setLong(6, record.quantity());
                ps.setBigDecimal(7, record.amount());
                ps.setBoolean(8, record.success());
                ps.setString(9, record.resultStatus());
                ps.executeUpdate();
                future.complete(null);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to record transaction", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Map<String, Integer>> loadStockSnapshot() {
        CompletableFuture<Map<String, Integer>> future = new CompletableFuture<>();
        SchedulerUtil.runAsync(plugin, () -> {
            Map<String, Integer> result = new HashMap<>();
            try (Connection conn = dataSource.getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT item_id, current_stock FROM aurorashop_stock_state")) {
                while (rs.next()) {
                    result.put(rs.getString("item_id"), rs.getInt("current_stock"));
                }
                future.complete(result);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load stock snapshot", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Void> saveStockSnapshot(Map<String, Integer> currentStockByItemId) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        SchedulerUtil.runAsync(plugin, () -> {
            String upsert = upsertStockSql();
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(upsert)) {
                    for (Map.Entry<String, Integer> entry : currentStockByItemId.entrySet()) {
                        ps.setString(1, entry.getKey());
                        ps.setInt(2, entry.getValue());
                        ps.setInt(3, entry.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                future.complete(null);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save stock snapshot", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Map<String, Integer>> loadDailyUsageSnapshot(long dayEpoch) {
        CompletableFuture<Map<String, Integer>> future = new CompletableFuture<>();
        SchedulerUtil.runAsync(plugin, () -> {
            Map<String, Integer> result = new HashMap<>();
            String sql = "SELECT composite_key, count FROM aurorashop_daily_usage WHERE day_epoch = ?";
            try (Connection conn = dataSource.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, dayEpoch);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getString("composite_key"), rs.getInt("count"));
                    }
                }
                future.complete(result);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load daily usage snapshot", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Void> saveDailyUsageSnapshot(long dayEpoch, Map<String, Integer> countByCompositeKey) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        SchedulerUtil.runAsync(plugin, () -> {
            String upsert = upsertDailyUsageSql();
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(upsert)) {
                    for (Map.Entry<String, Integer> entry : countByCompositeKey.entrySet()) {
                        ps.setLong(1, dayEpoch);
                        ps.setString(2, entry.getKey());
                        ps.setInt(3, entry.getValue());
                        ps.setInt(4, entry.getValue());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                conn.commit();
                future.complete(null);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save daily usage snapshot", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<StatsAggregate> loadStatsAggregate() {
        CompletableFuture<StatsAggregate> future = new CompletableFuture<>();
        SchedulerUtil.runAsync(plugin, () -> {
            try (Connection conn = dataSource.getConnection()) {
                long totalBought = 0, totalSold = 0, totalTransactions = 0, failedTransactions = 0;
                BigDecimal totalSpent = BigDecimal.ZERO, totalEarned = BigDecimal.ZERO;

                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT type, success, COUNT(*) AS cnt, "
                             + "COALESCE(SUM(quantity),0) AS qty, COALESCE(SUM(amount),0) AS amt "
                             + "FROM aurorashop_transactions GROUP BY type, success")) {
                    while (rs.next()) {
                        String type = rs.getString("type");
                        boolean success = rs.getBoolean("success");
                        long count = rs.getLong("cnt");
                        long qty = rs.getLong("qty");
                        BigDecimal amt = rs.getBigDecimal("amt");
                        totalTransactions += count;
                        if (!success) {
                            failedTransactions += count;
                            continue;
                        }
                        if ("BUY".equals(type)) {
                            totalBought += qty;
                            totalSpent = totalSpent.add(amt);
                        } else if ("SELL".equals(type)) {
                            totalSold += qty;
                            totalEarned = totalEarned.add(amt);
                        }
                    }
                }

                Map<String, StatsAggregate.ItemAggregate> perItem = new HashMap<>();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT item_id, type, COUNT(*) AS cnt, "
                             + "COALESCE(SUM(quantity),0) AS qty, COALESCE(SUM(amount),0) AS amt "
                             + "FROM aurorashop_transactions WHERE success = " + trueLiteral()
                             + " GROUP BY item_id, type")) {
                    Map<String, long[]> counts = new HashMap<>(); // [boughtQty, soldQty, txCount]
                    Map<String, BigDecimal[]> money = new HashMap<>(); // [spent, earned]
                    while (rs.next()) {
                        String itemId = rs.getString("item_id");
                        String type = rs.getString("type");
                        long qty = rs.getLong("qty");
                        long cnt = rs.getLong("cnt");
                        BigDecimal amt = rs.getBigDecimal("amt");
                        long[] c = counts.computeIfAbsent(itemId, k -> new long[3]);
                        BigDecimal[] m = money.computeIfAbsent(itemId, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                        c[2] += cnt;
                        if ("BUY".equals(type)) {
                            c[0] += qty;
                            m[0] = m[0].add(amt);
                        } else if ("SELL".equals(type)) {
                            c[1] += qty;
                            m[1] = m[1].add(amt);
                        }
                        counts.put(itemId, c);
                        money.put(itemId, m);
                    }
                    for (String itemId : counts.keySet()) {
                        long[] c = counts.get(itemId);
                        BigDecimal[] m = money.get(itemId);
                        perItem.put(itemId, new StatsAggregate.ItemAggregate(c[0], c[1], m[0], m[1], c[2]));
                    }
                }

                future.complete(new StatsAggregate(totalBought, totalSold, totalSpent, totalEarned,
                        totalTransactions, failedTransactions, perItem));
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load stats aggregate", e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    protected String trueLiteral() {
        return "TRUE";
    }

    protected abstract String upsertStockSql();

    protected abstract String upsertDailyUsageSql();
}
