package com.aurorashop;

import com.aurorashop.commands.SellAllCommand;
import com.aurorashop.commands.SellHandCommand;
import com.aurorashop.commands.ShopAdminCommand;
import com.aurorashop.commands.ShopCommand;
import com.aurorashop.config.ConfigManager;
import com.aurorashop.config.MessageManager;
import com.aurorashop.db.DatabaseManager;
import com.aurorashop.db.MySQLDatabaseManager;
import com.aurorashop.db.SQLiteDatabaseManager;
import com.aurorashop.economy.EconomyService;
import com.aurorashop.gui.GuiItemFactory;
import com.aurorashop.gui.GuiKeys;
import com.aurorashop.gui.ShopGuiListener;
import com.aurorashop.gui.ShopGuiManager;
import com.aurorashop.listeners.PlayerCleanupListener;
import com.aurorashop.placeholder.AuroraShopPlaceholders;
import com.aurorashop.shop.PriceValidator;
import com.aurorashop.shop.ShopRegistry;
import com.aurorashop.stats.StatisticsService;
import com.aurorashop.stock.StockService;
import com.aurorashop.transaction.SellExecutor;
import com.aurorashop.transaction.TransactionGuard;
import com.aurorashop.transaction.TransactionService;
import com.aurorashop.util.SchedulerUtil;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class AuroraShopPlugin extends JavaPlugin {

    private ConfigManager configManager;
    private MessageManager messageManager;
    private ShopRegistry shopRegistry;
    private PriceValidator priceValidator;
    private EconomyService economyService;
    private DatabaseManager databaseManager;
    private StockService stockService;
    private StatisticsService statisticsService;
    private TransactionService transactionService;
    private SellExecutor sellExecutor;
    private ShopGuiManager shopGuiManager;

    @Override
    public void onEnable() {
        configManager = new ConfigManager(this);
        configManager.load();

        messageManager = new MessageManager(this);
        messageManager.load();

        // Dependency check #1: Vault + a registered Economy provider. Required — fail safely and stop.
        economyService = new EconomyService(this);
        if (!economyService.setup()) {
            getLogger().severe("AuroraShop cannot start without Vault and a working Economy provider. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        shopRegistry = new ShopRegistry(this);
        if (!shopRegistry.load()) {
            getLogger().severe("AuroraShop cannot start — shops.yml/prices.yml failed to load. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        priceValidator = new PriceValidator(shopRegistry);
        logPricingWarnings(priceValidator.validate());

        // Dependency check #2: storage backend. Required — fail safely and stop rather than
        // run with a half-working (or silently no-op) persistence layer.
        try {
            databaseManager = createDatabaseManager();
            databaseManager.initialize();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "AuroraShop's database failed to initialize. Disabling to avoid running "
                    + "without working statistics/stock persistence.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        stockService = new StockService(this, shopRegistry, configManager, databaseManager);
        stockService.initializeStockFromConfig();
        stockService.loadPersistedState(); // startup-only; see StockService#loadPersistedState
        stockService.startPeriodicFlush();
        scheduleAutomaticRestock();

        statisticsService = new StatisticsService(this, databaseManager, configManager);
        statisticsService.initialize();

        TransactionGuard transactionGuard = new TransactionGuard();
        transactionService = new TransactionService(this, shopRegistry, configManager, economyService,
                stockService, statisticsService, transactionGuard);
        sellExecutor = new SellExecutor(shopRegistry, transactionService);

        GuiKeys guiKeys = new GuiKeys(this);
        GuiItemFactory guiItemFactory = new GuiItemFactory(configManager, guiKeys);
        shopGuiManager = new ShopGuiManager(configManager, shopRegistry, guiItemFactory, economyService, stockService, transactionService);
        ShopGuiListener shopGuiListener = new ShopGuiListener(getLogger(), guiKeys, shopGuiManager, shopRegistry,
                transactionService, sellExecutor, economyService, stockService, configManager, messageManager);

        getServer().getPluginManager().registerEvents(shopGuiListener, this);
        getServer().getPluginManager().registerEvents(new PlayerCleanupListener(transactionGuard, shopGuiManager), this);

        registerCommands();
        setupPlaceholderApiIfPresent();
        logCompatibilityInfo();

        getLogger().info("AuroraShop enabled: " + shopRegistry.allItems().size() + " items across "
                + shopRegistry.categoriesOrdered().size() + " categories. Storage: " + configManager.databaseType() + ".");
    }

    @Override
    public void onDisable() {
        if (stockService != null) {
            try {
                stockService.flush().get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                getLogger().warning("Final stock/usage save did not finish before shutdown (" + e.getMessage()
                        + "); at most the last few minutes of stock/limit activity may need to be re-derived on next start.");
            }
        }
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
    }

    /**
     * Reloads every configuration file and re-validates prices. Deliberately
     * does NOT reload persisted stock/daily-usage state from the database —
     * see {@code StockService#loadPersistedState} for why that must stay
     * startup-only.
     */
    public ReloadResult reloadAll() {
        try {
            configManager.load();
            messageManager.load();
            boolean loaded = shopRegistry.load();
            if (!loaded) {
                return new ReloadResult(false, 0, 0, List.of());
            }
            stockService.initializeStockFromConfig();
            List<String> warnings = priceValidator.validate();
            logPricingWarnings(warnings);
            return new ReloadResult(true, shopRegistry.allItems().size(), shopRegistry.categoriesOrdered().size(), warnings);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "AuroraShop reload failed; the previous configuration remains active.", e);
            return new ReloadResult(false, 0, 0, List.of());
        }
    }

    private DatabaseManager createDatabaseManager() {
        String type = configManager.databaseType();
        if (type.equals("MYSQL") || type.equals("MARIADB")) {
            getLogger().info("Using MySQL/MariaDB storage backend.");
            return new MySQLDatabaseManager(this, configManager);
        }
        getLogger().info("Using SQLite storage backend (default).");
        return new SQLiteDatabaseManager(this);
    }

    private void scheduleAutomaticRestock() {
        long intervalTicks = Math.max(1, configManager.autoRestockIntervalMinutes()) * 60L * 20L;
        SchedulerUtil.runAsyncRepeating(this, stockService::runAutomaticRestock, intervalTicks, intervalTicks);
    }

    private void registerCommands() {
        ShopCommand shopCommand = new ShopCommand(shopRegistry, shopGuiManager, messageManager);
        bindCommand("shop", shopCommand, shopCommand);

        SellAllCommand sellAllCommand = new SellAllCommand(sellExecutor, messageManager, economyService);
        bindCommand("sellall", sellAllCommand, null);
        bindCommand("sellinventory", sellAllCommand, null);

        SellHandCommand sellHandCommand = new SellHandCommand(sellExecutor, shopRegistry, messageManager, economyService);
        bindCommand("sellhand", sellHandCommand, null);

        ShopAdminCommand adminCommand = new ShopAdminCommand(this::reloadAll, shopRegistry, priceValidator,
                stockService, statisticsService, economyService, messageManager, getLogger());
        bindCommand("shopadmin", adminCommand, adminCommand);
    }

    private void bindCommand(String name, CommandExecutor executor, TabCompleter completer) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command '" + name + "' is missing from plugin.yml.");
            return;
        }
        command.setExecutor(executor);
        if (completer != null) {
            command.setTabCompleter(completer);
        }
    }

    private void setupPlaceholderApiIfPresent() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new AuroraShopPlaceholders(this, shopRegistry, statisticsService, economyService).register();
            getLogger().info("PlaceholderAPI detected — placeholders registered under %aurorashop_*%.");
        }
    }

    private void logCompatibilityInfo() {
        List<String> found = new ArrayList<>();
        if (getServer().getPluginManager().getPlugin("Essentials") != null) {
            found.add("EssentialsX");
        }
        if (getServer().getPluginManager().getPlugin("EconomyGUI") != null) {
            found.add("EconomyGUI");
        }
        if (!found.isEmpty()) {
            getLogger().info("Detected compatible plugin(s): " + String.join(", ", found));
        }
        economyService.providerName().ifPresent(name -> getLogger().info("Vault economy provider in use: " + name));
    }

    private void logPricingWarnings(List<String> warnings) {
        if (warnings.isEmpty()) {
            getLogger().info("Price validation passed: no arbitrage loops or pricing inversions detected.");
            return;
        }
        getLogger().warning("Price validation found " + warnings.size() + " potential problem(s):");
        for (String warning : warnings) {
            getLogger().warning(" - " + warning);
        }
    }
}
