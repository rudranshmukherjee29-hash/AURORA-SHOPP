package com.aurorashop.shop;

import com.aurorashop.model.ShopItem;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks the currently-loaded catalogue for pricing problems: direct
 * buy/sell inversions, and buy → convert → sell profit loops across the
 * three conversion relationships a {@code ShopItem} can declare (smelting,
 * block compression, and multi-component crafting). See prices.yml's
 * header comment for exactly what each relationship means.
 * <p>
 * This never blocks startup — a pricing problem is a balance risk, not a
 * security one, so we log/report it clearly and keep running.
 */
public final class PriceValidator {

    private final ShopRegistry registry;

    public PriceValidator(ShopRegistry registry) {
        this.registry = registry;
    }

    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        for (ShopItem item : registry.allItems()) {
            checkDirectInversion(item, problems);
            checkNonPositivePrices(item, problems);
            item.conversions().ifPresent(conv -> {
                conv.smeltsFrom().ifPresent(sourceId -> checkSmelting(item, sourceId, problems));
                conv.compressesInto().ifPresent(link -> checkCompression(item, link, problems));
                conv.craftsFrom().ifPresent(recipe -> checkCrafting(item, recipe, problems));
            });
        }
        return problems;
    }

    private void checkDirectInversion(ShopItem item, List<String> problems) {
        if (item.buyEnabled() && item.sellEnabled() && item.sellPrice().compareTo(item.buyPrice()) >= 0) {
            problems.add(item.id() + ": sell price (" + item.sellPrice() + ") is not lower than buy price ("
                    + item.buyPrice() + ") — direct buy/sell profit is possible.");
        }
    }

    private void checkNonPositivePrices(ShopItem item, List<String> problems) {
        if (item.buyEnabled() && item.buyPrice().compareTo(BigDecimal.ZERO) <= 0) {
            problems.add(item.id() + ": buying is enabled but buy price is not positive (" + item.buyPrice() + ").");
        }
        if (item.sellEnabled() && item.sellPrice().compareTo(BigDecimal.ZERO) <= 0) {
            problems.add(item.id() + ": selling is enabled but sell price is not positive (" + item.sellPrice() + ").");
        }
    }

    /** 1:1 furnace conversion: buying the raw input and smelting it must not out-earn selling it. */
    private void checkSmelting(ShopItem output, String sourceId, List<String> problems) {
        ShopItem source = registry.item(sourceId).orElse(null);
        if (source == null) {
            problems.add(output.id() + ": smeltsFrom references unknown item '" + sourceId + "'.");
            return;
        }
        if (!source.buyEnabled() || !output.sellEnabled()) {
            return; // no way to actually run the loop
        }
        BigDecimal cost = source.buyPrice();
        BigDecimal revenue = output.sellPrice();
        if (revenue.compareTo(cost) > 0) {
            problems.add(output.id() + ": smelting profit loop — buy " + source.id() + " for " + cost
                    + ", smelt into " + output.id() + ", sell for " + revenue + ".");
        }
    }

    /**
     * Block-compression pair. Checked in both directions: {@code ratio}
     * units of the base item craft into 1 of the target, and vanilla also
     * lets 1 target be crafted back into {@code ratio} of the base item.
     */
    private void checkCompression(ShopItem base, ShopItem.CompressionLink link, List<String> problems) {
        ShopItem target = registry.item(link.targetItemId()).orElse(null);
        if (target == null) {
            problems.add(base.id() + ": compressesInto references unknown item '" + link.targetItemId() + "'.");
            return;
        }
        int ratio = link.ratio();

        // Forward: buy `ratio` of base, compress, sell 1 of target.
        if (base.buyEnabled() && target.sellEnabled()) {
            BigDecimal cost = base.buyPrice().multiply(BigDecimal.valueOf(ratio));
            BigDecimal revenue = target.sellPrice();
            if (revenue.compareTo(cost) > 0) {
                problems.add(base.id() + "->" + target.id() + ": compression profit loop — buy " + ratio + "x "
                        + base.id() + " for " + cost + " total, compress, sell 1x " + target.id() + " for " + revenue + ".");
            }
        }

        // Reverse: buy 1 of target, decompress, sell `ratio` of base.
        if (target.buyEnabled() && base.sellEnabled()) {
            BigDecimal cost = target.buyPrice();
            BigDecimal revenue = base.sellPrice().multiply(BigDecimal.valueOf(ratio));
            if (revenue.compareTo(cost) > 0) {
                problems.add(target.id() + "->" + base.id() + ": decompression profit loop — buy 1x " + target.id()
                        + " for " + cost + ", decompress, sell " + ratio + "x " + base.id() + " for " + revenue + ".");
            }
        }
    }

    /** Multi-component crafting recipe, checked forward only (no vanilla "uncraft" for these). */
    private void checkCrafting(ShopItem output, ShopItem.CraftRecipe recipe, List<String> problems) {
        if (recipe.components().isEmpty()) {
            return; // informational-only entry with no priced components to check
        }
        if (!output.sellEnabled()) {
            return;
        }
        BigDecimal cost = BigDecimal.ZERO;
        for (ShopItem.CraftComponent component : recipe.components()) {
            ShopItem componentItem = registry.item(component.itemId()).orElse(null);
            if (componentItem == null) {
                problems.add(output.id() + ": craftsFrom references unknown item '" + component.itemId() + "'.");
                return;
            }
            if (!componentItem.buyEnabled()) {
                return; // can't run the loop if a required component can't be bought
            }
            cost = cost.add(componentItem.buyPrice().multiply(BigDecimal.valueOf(component.amount())));
        }
        BigDecimal revenue = output.sellPrice().multiply(BigDecimal.valueOf(recipe.outputAmount()));
        if (revenue.compareTo(cost) > 0) {
            problems.add(output.id() + ": crafting profit loop — buying the components costs " + cost
                    + " but crafting and selling " + recipe.outputAmount() + "x " + output.id() + " yields " + revenue + ".");
        }
    }
}
