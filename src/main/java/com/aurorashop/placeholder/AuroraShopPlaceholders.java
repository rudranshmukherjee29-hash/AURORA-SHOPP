package com.aurorashop.placeholder;

import com.aurorashop.economy.EconomyService;
import com.aurorashop.model.ShopItem;
import com.aurorashop.shop.ShopRegistry;
import com.aurorashop.stats.ItemStatEntry;
import com.aurorashop.stats.StatisticsService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registered only if PlaceholderAPI is present (see AuroraShopPlugin#onEnable)
 * — this class is never referenced, and therefore never classloaded, on a
 * server without it, which is what makes it safe as a soft dependency.
 */
public final class AuroraShopPlaceholders extends PlaceholderExpansion {

    private final JavaPlugin plugin;
    private final ShopRegistry registry;
    private final StatisticsService stats;
    private final EconomyService economy;

    public AuroraShopPlaceholders(JavaPlugin plugin, ShopRegistry registry, StatisticsService stats, EconomyService economy) {
        this.plugin = plugin;
        this.registry = registry;
        this.stats = stats;
        this.economy = economy;
    }

    @Override
    public String getIdentifier() {
        return "aurorashop";
    }

    @Override
    public String getAuthor() {
        return "AuroraShop";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        String key = params.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "balance" -> player == null ? "" : economy.format(economy.getBalance(player));
            case "total_bought" -> String.valueOf(stats.totalBought());
            case "total_sold" -> String.valueOf(stats.totalSold());
            case "total_transactions" -> String.valueOf(stats.totalTransactions());
            case "total_spent" -> economy.format(stats.totalSpent());
            case "total_earned" -> economy.format(stats.totalEarned());
            case "most_purchased" -> topName(stats.topBought(1));
            case "most_sold" -> topName(stats.topSold(1));
            default -> handleItemPlaceholder(key);
        };
    }

    private String topName(List<Map.Entry<String, ItemStatEntry>> top) {
        if (top.isEmpty()) {
            return "";
        }
        return registry.item(top.get(0).getKey()).map(ShopItem::displayName).orElse(top.get(0).getKey());
    }

    /** Handles item_<id>_buy, item_<id>_sell, item_<id>_bought, item_<id>_sold. */
    private String handleItemPlaceholder(String params) {
        if (!params.startsWith("item_")) {
            return null;
        }
        String rest = params.substring("item_".length());
        int lastUnderscore = rest.lastIndexOf('_');
        if (lastUnderscore < 0) {
            return null;
        }
        String itemId = rest.substring(0, lastUnderscore);
        String field = rest.substring(lastUnderscore + 1);
        ShopItem item = registry.item(itemId).orElse(null);
        if (item == null) {
            return "";
        }
        return switch (field) {
            case "buy" -> item.buyEnabled() ? economy.format(item.buyPrice()) : "N/A";
            case "sell" -> item.sellEnabled() ? economy.format(item.sellPrice()) : "N/A";
            case "bought" -> stats.itemStats(itemId).map(e -> String.valueOf(e.boughtCount())).orElse("0");
            case "sold" -> stats.itemStats(itemId).map(e -> String.valueOf(e.soldCount())).orElse("0");
            default -> null;
        };
    }
}
