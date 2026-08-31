package com.aurorashop.gui;

import com.aurorashop.config.ConfigManager;
import com.aurorashop.model.ShopCategory;
import com.aurorashop.model.ShopItem;
import com.aurorashop.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Every ItemStack produced here is tagged via {@link GuiKeys} so the click
 * listener can identify it purely from server-side PersistentDataContainer
 * data — display name and lore below are cosmetic only.
 */
public final class GuiItemFactory {

    public static final String ACTION_ITEM = "ITEM";
    public static final String ACTION_NONE = "NONE";
    public static final String ACTION_CATEGORY_PREFIX = "CATEGORY:";
    public static final String ACTION_BACK = "BACK";
    public static final String ACTION_CLOSE = "CLOSE";
    public static final String ACTION_PREV_PAGE = "PREV_PAGE";
    public static final String ACTION_NEXT_PAGE = "NEXT_PAGE";
    public static final String ACTION_QUANTITY_TOGGLE = "QUANTITY_TOGGLE";
    public static final String ACTION_SEARCH_PROMPT = "SEARCH_PROMPT";
    public static final String ACTION_SELLHAND = "SELLHAND";
    public static final String ACTION_SELLALL = "SELLALL";
    public static final String ACTION_CONFIRM = "CONFIRM";
    public static final String ACTION_CANCEL = "CANCEL";

    private final ConfigManager config;
    private final GuiKeys keys;

    public GuiItemFactory(ConfigManager config, GuiKeys keys) {
        this.config = config;
        this.keys = keys;
    }

    public ItemStack categoryIcon(ShopCategory category) {
        ItemStack stack = new ItemStack(category.icon());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(TextUtil.parse(category.displayName()));
        tagAction(meta, ACTION_CATEGORY_PREFIX + category.id());
        stack.setItemMeta(meta);
        return stack;
    }

    public ItemStack shopItemIcon(ShopItem item, String buyPriceStr, String sellPriceStr,
                                   QuantityMode mode, Integer currentStock, Integer maxStock) {
        ItemStack stack = new ItemStack(item.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(TextUtil.parse("<white>" + item.displayName()));

        List<Component> lore = new ArrayList<>();
        if (item.buyEnabled()) {
            lore.add(TextUtil.parse(config.itemDisplayLine("lore-buy-enabled", "<gray>Buy: <green>{buy}"),
                    TextUtil.map("buy", buyPriceStr)));
        } else {
            lore.add(TextUtil.parse(config.itemDisplayLine("lore-buy-disabled", "<gray>Buy: <red>Unavailable")));
        }
        if (item.sellEnabled()) {
            lore.add(TextUtil.parse(config.itemDisplayLine("lore-sell-enabled", "<gray>Sell: <gold>{sell}"),
                    TextUtil.map("sell", sellPriceStr)));
        } else {
            lore.add(TextUtil.parse(config.itemDisplayLine("lore-sell-disabled", "<gray>Sell: <red>Unavailable")));
        }
        if (currentStock != null && maxStock != null) {
            lore.add(TextUtil.parse(config.itemDisplayLine("lore-stock", "<gray>Stock: <yellow>{stock}<gray>/<yellow>{stock_max}"),
                    TextUtil.map("stock", String.valueOf(currentStock), "stock_max", String.valueOf(maxStock))));
        }
        lore.add(TextUtil.parse(config.itemDisplayLine("lore-blank", "")));
        if (item.buyEnabled()) {
            lore.add(TextUtil.parse(config.itemDisplayLine("lore-hint-buy", "<dark_gray>Left-Click: <white>Buy {qty_mode}"),
                    TextUtil.map("qty_mode", mode.label())));
            lore.add(TextUtil.parse(config.itemDisplayLine("lore-hint-buy-stack", "<dark_gray>Shift + Left-Click: <white>Buy 64")));
        }
        if (item.sellEnabled()) {
            lore.add(TextUtil.parse(config.itemDisplayLine("lore-hint-sell", "<dark_gray>Right-Click: <white>Sell 1")));
            lore.add(TextUtil.parse(config.itemDisplayLine("lore-hint-sell-all", "<dark_gray>Shift + Right-Click: <white>Sell All")));
        }
        meta.lore(lore);

        tagAction(meta, ACTION_ITEM);
        meta.getPersistentDataContainer().set(keys.itemId, PersistentDataType.STRING, item.id());
        stack.setItemMeta(meta);
        return stack;
    }

    public ItemStack controlButton(Material material, String displayName, String action) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(TextUtil.parse(displayName));
        tagAction(meta, action);
        stack.setItemMeta(meta);
        return stack;
    }

    public ItemStack controlButton(Material material, String displayName, List<String> lore, String action) {
        ItemStack stack = controlButton(material, displayName, action);
        ItemMeta meta = stack.getItemMeta();
        List<Component> parsedLore = new ArrayList<>();
        for (String line : lore) {
            parsedLore.add(TextUtil.parse(line));
        }
        meta.lore(parsedLore);
        stack.setItemMeta(meta);
        return stack;
    }

    public ItemStack decorativePane(Material material) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.empty());
        tagAction(meta, ACTION_NONE);
        stack.setItemMeta(meta);
        return stack;
    }

    private void tagAction(ItemMeta meta, String action) {
        meta.getPersistentDataContainer().set(keys.action, PersistentDataType.STRING, action);
    }
}
