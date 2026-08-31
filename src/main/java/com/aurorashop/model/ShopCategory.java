package com.aurorashop.model;

import org.bukkit.Material;

/**
 * A category of items in the shop GUI (e.g. "Blocks", "Ores & Minerals").
 * Immutable — rebuilt on reload just like {@link ShopItem}.
 */
public record ShopCategory(String id, String displayName, Material icon, int order) {
}
