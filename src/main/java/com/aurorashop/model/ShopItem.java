package com.aurorashop.model;

import org.bukkit.Material;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Immutable, fully-resolved configuration for one shop item. Instances are
 * built once by {@code ShopRegistry} when {@code prices.yml} is (re)loaded
 * and are never mutated afterwards — any change to price, stock limits, or
 * enabled state requires building a new {@code ShopItem} and swapping the
 * registry's reference, which keeps every read of this object thread-safe
 * without needing per-field synchronization.
 * <p>
 * Live, mutable state (current stock count, per-player daily usage) lives
 * in {@code StockService}, keyed by {@link #id()}, precisely so that this
 * object can remain immutable.
 */
public final class ShopItem {

    private final String id;
    private final Material material;
    private final String categoryId;
    private final String displayName;
    private final BigDecimal buyPrice;
    private final BigDecimal sellPrice;
    private final boolean buyEnabled;
    private final boolean sellEnabled;
    private final Integer dailyBuyLimit;
    private final Integer dailySellLimit;
    private final StockConfig stockConfig; // null = unlimited
    private final Conversions conversions; // null = no known conversion relationships

    public ShopItem(String id, Material material, String categoryId, String displayName,
                     BigDecimal buyPrice, BigDecimal sellPrice, boolean buyEnabled, boolean sellEnabled,
                     Integer dailyBuyLimit, Integer dailySellLimit,
                     StockConfig stockConfig, Conversions conversions) {
        this.id = id;
        this.material = material;
        this.categoryId = categoryId;
        this.displayName = displayName;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.buyEnabled = buyEnabled;
        this.sellEnabled = sellEnabled;
        this.dailyBuyLimit = dailyBuyLimit;
        this.dailySellLimit = dailySellLimit;
        this.stockConfig = stockConfig;
        this.conversions = conversions;
    }

    public String id() {
        return id;
    }

    public Material material() {
        return material;
    }

    public String categoryId() {
        return categoryId;
    }

    public String displayName() {
        return displayName;
    }

    public BigDecimal buyPrice() {
        return buyPrice;
    }

    public BigDecimal sellPrice() {
        return sellPrice;
    }

    public boolean buyEnabled() {
        return buyEnabled;
    }

    public boolean sellEnabled() {
        return sellEnabled;
    }

    public Optional<Integer> dailyBuyLimit() {
        return Optional.ofNullable(dailyBuyLimit);
    }

    public Optional<Integer> dailySellLimit() {
        return Optional.ofNullable(dailySellLimit);
    }

    public boolean hasLimitedStock() {
        return stockConfig != null;
    }

    public Optional<StockConfig> stockConfig() {
        return Optional.ofNullable(stockConfig);
    }

    public Optional<Conversions> conversions() {
        return Optional.ofNullable(conversions);
    }

    /**
     * Exact price for {@code quantity} units, computed in BigDecimal to avoid
     * floating-point drift, rounded to 2 decimal places (standard currency
     * precision) using HALF_UP rounding.
     */
    public BigDecimal totalBuyPrice(long quantity) {
        return buyPrice.multiply(BigDecimal.valueOf(quantity))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    public BigDecimal totalSellPrice(long quantity) {
        return sellPrice.multiply(BigDecimal.valueOf(quantity))
                .setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** Configuration for a per-item stock cap. */
    public static final class StockConfig {
        private final int max;
        private final int initialCurrent;
        private final boolean automaticRestock;

        public StockConfig(int max, int initialCurrent, boolean automaticRestock) {
            this.max = max;
            this.initialCurrent = initialCurrent;
            this.automaticRestock = automaticRestock;
        }

        public int max() {
            return max;
        }

        public int initialCurrent() {
            return initialCurrent;
        }

        public boolean automaticRestock() {
            return automaticRestock;
        }
    }

    /** Declared conversion relationships used by {@code PriceValidator}. */
    public static final class Conversions {
        private final String smeltsFrom;                 // item id, 1:1, nullable
        private final CompressionLink compressesInto;     // nullable
        private final CraftRecipe craftsFrom;             // nullable

        public Conversions(String smeltsFrom, CompressionLink compressesInto, CraftRecipe craftsFrom) {
            this.smeltsFrom = smeltsFrom;
            this.compressesInto = compressesInto;
            this.craftsFrom = craftsFrom;
        }

        public Optional<String> smeltsFrom() {
            return Optional.ofNullable(smeltsFrom);
        }

        public Optional<CompressionLink> compressesInto() {
            return Optional.ofNullable(compressesInto);
        }

        public Optional<CraftRecipe> craftsFrom() {
            return Optional.ofNullable(craftsFrom);
        }
    }

    public record CompressionLink(String targetItemId, int ratio) {
    }

    public record CraftComponent(String itemId, int amount) {
    }

    public record CraftRecipe(int outputAmount, List<CraftComponent> components) {
    }
}
