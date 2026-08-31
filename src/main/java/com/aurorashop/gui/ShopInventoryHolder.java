package com.aurorashop.gui;

import com.aurorashop.model.TransactionType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Marks an {@link Inventory} as belonging to AuroraShop, and carries
 * whatever state that particular screen needs. The click listener's very
 * first check is "is the top inventory's holder one of these" — if not,
 * AuroraShop touches nothing about the event at all, so we can never
 * interfere with another plugin's GUI.
 * <p>
 * This is intentionally a single class with a {@link Type} discriminator
 * rather than four subclasses: every screen needs the same handful of
 * optional fields, and one holder per open inventory is simpler to reason
 * about than a small type hierarchy for this amount of state.
 */
public final class ShopInventoryHolder implements InventoryHolder {

    public enum Type { MAIN_MENU, CATEGORY, SEARCH, CONFIRM }

    private final Type type;
    private final String categoryId;   // CATEGORY only
    private final String searchQuery;  // SEARCH only
    private final PendingConfirmation pendingConfirmation; // CONFIRM only
    private int page;
    private Inventory inventory;

    private ShopInventoryHolder(Type type, String categoryId, String searchQuery, PendingConfirmation pendingConfirmation) {
        this.type = type;
        this.categoryId = categoryId;
        this.searchQuery = searchQuery;
        this.pendingConfirmation = pendingConfirmation;
    }

    public static ShopInventoryHolder mainMenu() {
        return new ShopInventoryHolder(Type.MAIN_MENU, null, null, null);
    }

    public static ShopInventoryHolder category(String categoryId, int page) {
        ShopInventoryHolder holder = new ShopInventoryHolder(Type.CATEGORY, categoryId, null, null);
        holder.page = page;
        return holder;
    }

    public static ShopInventoryHolder search(String query, int page) {
        ShopInventoryHolder holder = new ShopInventoryHolder(Type.SEARCH, null, query, null);
        holder.page = page;
        return holder;
    }

    public static ShopInventoryHolder confirm(PendingConfirmation pending) {
        return new ShopInventoryHolder(Type.CONFIRM, null, null, pending);
    }

    public Type type() {
        return type;
    }

    public String categoryId() {
        return categoryId;
    }

    public String searchQuery() {
        return searchQuery;
    }

    public int page() {
        return page;
    }

    public PendingConfirmation pendingConfirmation() {
        return pendingConfirmation;
    }

    void bindInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    /** Package-private: lets ShopGuiManager clamp the page to a valid range after construction. */
    void setPageInternal(int page) {
        this.page = page;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** A buy/sell action awaiting explicit player confirmation (large transactions only). */
    public record PendingConfirmation(TransactionType type, String itemId, long quantity, boolean sellAll,
                                       String returnCategoryId, String returnSearchQuery, int returnPage) {
    }
}
