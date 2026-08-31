package com.aurorashop.gui;

/**
 * Tiny per-player state that outlives any single open inventory. Currently
 * just the quantity mode; kept as its own class so it's obvious where to
 * add more (e.g. a remembered search query) without overloading
 * {@link ShopInventoryHolder}, which only describes the currently-open
 * screen.
 */
public final class PlayerGuiSession {
    private volatile QuantityMode quantityMode = QuantityMode.ONE;

    public QuantityMode quantityMode() {
        return quantityMode;
    }

    public void setQuantityMode(QuantityMode mode) {
        this.quantityMode = mode;
    }
}
