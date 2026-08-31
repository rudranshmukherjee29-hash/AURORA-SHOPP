package com.aurorashop.db;

import com.aurorashop.config.ConfigManager;
import com.zaxxer.hikari.HikariConfig;
import org.bukkit.plugin.java.JavaPlugin;

public final class MySQLDatabaseManager extends AbstractSqlDatabaseManager {

    private final ConfigManager config;

    public MySQLDatabaseManager(JavaPlugin plugin, ConfigManager config) {
        super(plugin);
        this.config = config;
    }

    @Override
    protected HikariConfig buildHikariConfig() {
        HikariConfig hikari = new HikariConfig();
        String url = "jdbc:mysql://" + config.mysqlHost() + ":" + config.mysqlPort() + "/" + config.mysqlDatabase()
                + "?useSSL=" + config.mysqlUseSSL() + "&characterEncoding=utf8mb4&useUnicode=true";
        hikari.setJdbcUrl(url);
        hikari.setUsername(config.mysqlUsername());
        hikari.setPassword(config.mysqlPassword());
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.setMaximumPoolSize(Math.max(2, config.mysqlPoolSize()));
        hikari.setPoolName("AuroraShop-MySQL");
        return hikari;
    }

    @Override
    protected String autoIncrementPrimaryKeyType() {
        return "BIGINT AUTO_INCREMENT PRIMARY KEY";
    }

    @Override
    protected String upsertStockSql() {
        return "INSERT INTO aurorashop_stock_state (item_id, current_stock) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE current_stock = ?";
    }

    @Override
    protected String upsertDailyUsageSql() {
        return "INSERT INTO aurorashop_daily_usage (day_epoch, composite_key, count) VALUES (?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE count = ?";
    }
}
