package com.aurorashop.gui;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Every clickable icon AuroraShop places in a GUI carries these
 * PersistentDataContainer tags. Per design brief section 9 ("Do not
 * identify shop items solely by display name, lore, or custom model
 * data"), the click handler reads ONLY these keys to decide what an icon
 * does — display name and lore are cosmetic and never trusted.
 */
public final class GuiKeys {

    public final NamespacedKey itemId;
    public final NamespacedKey action;

    public GuiKeys(JavaPlugin plugin) {
        this.itemId = new NamespacedKey(plugin, "shop_item_id");
        this.action = new NamespacedKey(plugin, "shop_action");
    }
}
