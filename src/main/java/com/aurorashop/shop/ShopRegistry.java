package com.aurorashop.shop;

import com.aurorashop.model.ShopCategory;
import com.aurorashop.model.ShopItem;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Holds the currently-loaded catalogue (categories + items). A reload
 * builds an entirely new immutable snapshot and only swaps it in once
 * fully parsed without error — a malformed edit to prices.yml can never
 * leave the shop half-updated or serving stale/mismatched data.
 */
public final class ShopRegistry {

    private final JavaPlugin plugin;

    private volatile Map<String, ShopCategory> categories = Collections.emptyMap();
    private volatile Map<String, ShopItem> items = Collections.emptyMap();
    private volatile Map<Material, List<String>> itemIdsByMaterial = Collections.emptyMap();

    public ShopRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Returns false if loading failed — caller should keep the previous snapshot in that case. */
    public boolean load() {
        File shopsFile = new File(plugin.getDataFolder(), "shops.yml");
        File pricesFile = new File(plugin.getDataFolder(), "prices.yml");
        if (!shopsFile.exists()) {
            plugin.saveResource("shops.yml", false);
        }
        if (!pricesFile.exists()) {
            plugin.saveResource("prices.yml", false);
        }

        try {
            Map<String, ShopCategory> newCategories = parseCategories(YamlConfiguration.loadConfiguration(shopsFile));
            Map<String, ShopItem> newItems = parseItems(YamlConfiguration.loadConfiguration(pricesFile), newCategories);
            Map<Material, List<String>> newIndex = buildMaterialIndex(newItems);

            this.categories = newCategories;
            this.items = newItems;
            this.itemIdsByMaterial = newIndex;
            plugin.getLogger().info("Loaded " + newItems.size() + " shop items across " + newCategories.size() + " categories.");
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load shop configuration — keeping previous catalogue.", e);
            return false;
        }
    }

    private Map<String, ShopCategory> parseCategories(YamlConfiguration yaml) {
        Map<String, ShopCategory> result = new LinkedHashMap<>();
        List<Map<?, ?>> list = yaml.getMapList("categories");
        int order = 0;
        for (Map<?, ?> entry : list) {
            String id = String.valueOf(entry.get("id"));
            String display = String.valueOf(entry.get("display"));
            String iconName = String.valueOf(entry.get("icon"));
            Material icon = Material.matchMaterial(iconName);
            if (icon == null) {
                throw new IllegalStateException("Category '" + id + "' has invalid icon material '" + iconName + "'");
            }
            result.put(id, new ShopCategory(id, display, icon, order++));
        }
        if (result.isEmpty()) {
            throw new IllegalStateException("shops.yml defines no categories");
        }
        return Collections.unmodifiableMap(result);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ShopItem> parseItems(YamlConfiguration yaml, Map<String, ShopCategory> categories) {
        Map<String, ShopItem> result = new LinkedHashMap<>();
        List<Map<?, ?>> list = yaml.getMapList("items");
        for (Map<?, ?> raw : list) {
            String id = requireString(raw, "id");
            if (result.containsKey(id)) {
                throw new IllegalStateException("Duplicate item id: " + id);
            }
            String materialName = requireString(raw, "material");
            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                throw new IllegalStateException("Item '" + id + "' has invalid material '" + materialName + "'");
            }
            String categoryId = requireString(raw, "category");
            if (!categories.containsKey(categoryId)) {
                throw new IllegalStateException("Item '" + id + "' references unknown category '" + categoryId + "'");
            }
            String displayName = raw.containsKey("displayName") ? String.valueOf(raw.get("displayName")) : id;
            BigDecimal buy = toBigDecimal(raw.get("buy"), BigDecimal.ZERO);
            BigDecimal sell = toBigDecimal(raw.get("sell"), BigDecimal.ZERO);
            boolean buyEnabled = raw.containsKey("buyEnabled") ? (Boolean) raw.get("buyEnabled") : true;
            boolean sellEnabled = raw.containsKey("sellEnabled") ? (Boolean) raw.get("sellEnabled") : true;
            Integer dailyBuyLimit = raw.containsKey("dailyBuyLimit") ? ((Number) raw.get("dailyBuyLimit")).intValue() : null;
            Integer dailySellLimit = raw.containsKey("dailySellLimit") ? ((Number) raw.get("dailySellLimit")).intValue() : null;

            ShopItem.StockConfig stockConfig = null;
            if (raw.get("stock") instanceof Map<?, ?> stockMap) {
                int max = ((Number) stockMap.get("max")).intValue();
                int current = stockMap.containsKey("current") ? ((Number) stockMap.get("current")).intValue() : max;
                boolean automatic = stockMap.containsKey("automaticRestock") && (Boolean) stockMap.get("automaticRestock");
                stockConfig = new ShopItem.StockConfig(max, current, automatic);
            }

            ShopItem.Conversions conversions = null;
            if (raw.get("conversions") instanceof Map<?, ?> convMap) {
                String smeltsFrom = convMap.containsKey("smeltsFrom") ? String.valueOf(convMap.get("smeltsFrom")) : null;

                ShopItem.CompressionLink compressesInto = null;
                if (convMap.get("compressesInto") instanceof Map<?, ?> compMap) {
                    String target = String.valueOf(compMap.get("item"));
                    int ratio = ((Number) compMap.get("ratio")).intValue();
                    compressesInto = new ShopItem.CompressionLink(target, ratio);
                }

                ShopItem.CraftRecipe craftsFrom = null;
                if (convMap.get("craftsFrom") instanceof Map<?, ?> craftMap) {
                    int outputAmount = craftMap.containsKey("outputAmount") ? ((Number) craftMap.get("outputAmount")).intValue() : 1;
                    List<ShopItem.CraftComponent> components = new ArrayList<>();
                    Object componentsRaw = craftMap.get("components");
                    if (componentsRaw instanceof List<?> componentList) {
                        for (Object o : componentList) {
                            if (o instanceof Map<?, ?> compEntry) {
                                String compItem = String.valueOf(compEntry.get("item"));
                                int amount = ((Number) compEntry.get("amount")).intValue();
                                components.add(new ShopItem.CraftComponent(compItem, amount));
                            }
                        }
                    }
                    craftsFrom = new ShopItem.CraftRecipe(outputAmount, components);
                }

                conversions = new ShopItem.Conversions(smeltsFrom, compressesInto, craftsFrom);
            }

            result.put(id, new ShopItem(id, material, categoryId, displayName, buy, sell,
                    buyEnabled, sellEnabled, dailyBuyLimit, dailySellLimit, stockConfig, conversions));
        }
        return Collections.unmodifiableMap(result);
    }

    private Map<Material, List<String>> buildMaterialIndex(Map<String, ShopItem> items) {
        Map<Material, List<String>> index = new LinkedHashMap<>();
        for (ShopItem item : items.values()) {
            index.computeIfAbsent(item.material(), m -> new ArrayList<>()).add(item.id());
        }
        return Collections.unmodifiableMap(index);
    }

    private static String requireString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing required field '" + key + "'");
        }
        return String.valueOf(value);
    }

    private static BigDecimal toBigDecimal(Object value, BigDecimal fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return fallback;
    }

    // ---------------------------------------------------------------
    // Read access
    // ---------------------------------------------------------------

    public java.util.Optional<ShopItem> item(String id) {
        return java.util.Optional.ofNullable(items.get(id));
    }

    public java.util.Optional<ShopCategory> category(String id) {
        return java.util.Optional.ofNullable(categories.get(id));
    }

    public List<ShopCategory> categoriesOrdered() {
        List<ShopCategory> list = new ArrayList<>(categories.values());
        list.sort(java.util.Comparator.comparingInt(ShopCategory::order));
        return Collections.unmodifiableList(list);
    }

    public List<ShopItem> itemsInCategory(String categoryId) {
        List<ShopItem> list = new ArrayList<>();
        for (ShopItem item : items.values()) {
            if (item.categoryId().equals(categoryId)) {
                list.add(item);
            }
        }
        return list;
    }

    public List<ShopItem> allItems() {
        return new ArrayList<>(items.values());
    }

    public List<String> itemIdsForMaterial(Material material) {
        return itemIdsByMaterial.getOrDefault(material, Collections.emptyList());
    }

    public List<ShopItem> search(String query) {
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        List<ShopItem> results = new ArrayList<>();
        for (ShopItem item : items.values()) {
            if (item.id().toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || item.displayName().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                results.add(item);
            }
        }
        return results;
    }
}
