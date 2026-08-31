package com.aurorashop.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.logging.Level;

/**
 * Loads and holds every YAML file AuroraShop reads other than
 * {@code prices.yml}/{@code shops.yml} (which {@code ShopRegistry} owns,
 * since those two are tightly coupled to item/category construction).
 * <p>
 * Every file is copied from the jar's bundled default on first run and
 * otherwise loaded as-is — we never overwrite a server owner's edits.
 */
public final class ConfigManager {

    private final JavaPlugin plugin;

    private FileConfiguration config;
    private FileConfiguration limits;
    private FileConfiguration gui;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        config = loadOrCreate("config.yml");
        limits = loadOrCreate("limits.yml");
        gui = loadOrCreate("gui.yml");
    }

    private FileConfiguration loadOrCreate(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        // Layer bundled defaults underneath, so a partially-edited file
        // (missing keys added in a later plugin version) still resolves
        // sensible values instead of throwing NPEs deep in GUI code.
        InputStream defaultsStream = plugin.getResource(name);
        if (defaultsStream != null) {
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(defaultsStream, java.nio.charset.StandardCharsets.UTF_8));
            yaml.setDefaults(defaults);
        }
        return yaml;
    }

    public void save(String name, FileConfiguration configuration) {
        try {
            configuration.save(new File(plugin.getDataFolder(), name));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save " + name, e);
        }
    }

    // ---------------------------------------------------------------
    // config.yml
    // ---------------------------------------------------------------

    public String databaseType() {
        return config.getString("database.type", "SQLITE").toUpperCase(java.util.Locale.ROOT);
    }

    public String mysqlHost() {
        return config.getString("database.mysql.host", "localhost");
    }

    public int mysqlPort() {
        return config.getInt("database.mysql.port", 3306);
    }

    public String mysqlDatabase() {
        return config.getString("database.mysql.database", "aurorashop");
    }

    public String mysqlUsername() {
        return config.getString("database.mysql.username", "aurorashop");
    }

    public String mysqlPassword() {
        return config.getString("database.mysql.password", "");
    }

    public boolean mysqlUseSSL() {
        return config.getBoolean("database.mysql.useSSL", false);
    }

    public int mysqlPoolSize() {
        return config.getInt("database.mysql.poolSize", 10);
    }

    public long maxTransactionSize() {
        return config.getLong("transactions.maxTransactionSize", 10000);
    }

    public BigDecimal confirmationThreshold() {
        return BigDecimal.valueOf(config.getDouble("transactions.confirmationThreshold", 5000.0));
    }

    public long rateLimitMillis() {
        return config.getLong("transactions.rateLimitMillis", 150);
    }

    public boolean requireVanillaOnly() {
        return config.getBoolean("sell.requireVanillaOnly", true);
    }

    public boolean restockOnSell() {
        return config.getBoolean("sell.restockOnSell", false);
    }

    public int autoRestockIntervalMinutes() {
        return config.getInt("stock.autoRestockIntervalMinutes", 60);
    }

    public boolean logTransactionsToConsole() {
        return config.getBoolean("logging.logTransactionsToConsole", false);
    }

    public boolean logFailedTransactions() {
        return config.getBoolean("logging.logFailedTransactions", true);
    }

    // ---------------------------------------------------------------
    // limits.yml
    // ---------------------------------------------------------------

    public String bypassLimitsPermission() {
        return limits.getString("bypassPermission", "aurorashop.bypasslimits");
    }

    public int dailyResetHour() {
        return limits.getInt("dailyResetHour", 0);
    }

    public boolean autoRestockEnabled() {
        return limits.getBoolean("restock.enabled", true);
    }

    public boolean restockRefillToMax() {
        return limits.getBoolean("restock.refillToMax", true);
    }

    // ---------------------------------------------------------------
    // gui.yml
    // ---------------------------------------------------------------

    public String guiTitleMainMenu() {
        return gui.getString("titles.main-menu", "AuroraShop");
    }

    public String guiTitleCategory() {
        return gui.getString("titles.category", "AuroraShop - {category}");
    }

    public String guiTitleSearch() {
        return gui.getString("titles.search", "AuroraShop - Search: {query}");
    }

    public String guiTitleConfirm() {
        return gui.getString("titles.confirm", "Confirm Transaction");
    }

    public List<Integer> mainMenuCategorySlots() {
        return gui.getIntegerList("main-menu.category-slots");
    }

    public int mainMenuCloseSlot() {
        return gui.getInt("main-menu.close-slot", 49);
    }

    public int mainMenuBalanceSlot() {
        return gui.getInt("main-menu.balance-slot", 4);
    }

    public boolean mainMenuDecorativeBorder() {
        return gui.getBoolean("main-menu.decorative-border", true);
    }

    public String mainMenuBorderMaterial() {
        return gui.getString("main-menu.border-material", "BLACK_STAINED_GLASS_PANE");
    }

    public int categoryItemsPerPage() {
        return gui.getInt("category-menu.items-per-page", 45);
    }

    public int controlBarSlot(String key, int fallback) {
        return gui.getInt("category-menu.control-bar." + key, fallback);
    }

    public int confirmSlot(String key, int fallback) {
        return gui.getInt("confirm-menu." + key, fallback);
    }

    public boolean soundsEnabled() {
        return gui.getBoolean("sounds.enabled", true);
    }

    public String sound(String key, String fallback) {
        return gui.getString("sounds." + key, fallback);
    }

    public String itemDisplayLine(String key, String fallback) {
        return gui.getString("item-display." + key, fallback);
    }
}
