package com.aurorashop.db;

import com.zaxxer.hikari.HikariConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class SQLiteDatabaseManager extends AbstractSqlDatabaseManager {

    public SQLiteDatabaseManager(JavaPlugin plugin) {
        super(plugin);
    }

    @Override
    protected HikariConfig buildHikariConfig() {
        File dbFile = new File(plugin.getDataFolder(), "aurorashop.db");
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setDriverClassName("org.sqlite.JDBC");
        // SQLite has a single writer; keep the pool small so we don't queue
        // up contention that HikariCP itself would then have to arbitrate.
        config.setMaximumPoolSize(1);
        config.setPoolName("AuroraShop-SQLite");
        return config;
    }

    @Override
    protected String autoIncrementPrimaryKeyType() {
        return "INTEGER PRIMARY KEY AUTOINCREMENT";
    }

    @Override
    protected String trueLiteral() {
        return "1";
    }

    @Override
    protected String upsertStockSql() {
        return "INSERT INTO aurorashop_stock_state (item_id, current_stock) VALUES (?, ?) "
                + "ON CONFLICT(item_id) DO UPDATE SET current_stock = ?";
    }

    @Override
    protected String upsertDailyUsageSql() {
        return "INSERT INTO aurorashop_daily_usage (day_epoch, composite_key, count) VALUES (?, ?, ?) "
                + "ON CONFLICT(day_epoch, composite_key) DO UPDATE SET count = ?";
    }
}
