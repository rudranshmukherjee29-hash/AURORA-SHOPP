package com.aurorashop.gui;

import com.aurorashop.config.ConfigManager;
import com.aurorashop.economy.EconomyService;
import com.aurorashop.model.ShopCategory;
import com.aurorashop.model.ShopItem;
import com.aurorashop.shop.ShopRegistry;
import com.aurorashop.stock.StockService;
import com.aurorashop.transaction.TransactionService;
import com.aurorashop.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds every GUI screen fresh on each open — nothing about a shop
 * inventory is cached and re-shown, so prices, stock, and balance are
 * always current at the moment a player opens or pages through the menu.
 */
public final class ShopGuiManager {

    private final ConfigManager config;
    private final ShopRegistry registry;
    private final GuiItemFactory items;
    private final EconomyService economy;
    private final StockService stock;
    private final TransactionService transactions;

    private final Map<UUID, PlayerGuiSession> sessions = new ConcurrentHashMap<>();

    public ShopGuiManager(ConfigManager config, ShopRegistry registry, GuiItemFactory items,
                           EconomyService economy, StockService stock, TransactionService transactions) {
        this.config = config;
        this.registry = registry;
        this.items = items;
        this.economy = economy;
        this.stock = stock;
        this.transactions = transactions;
    }

    public PlayerGuiSession session(Player player) {
        return sessions.computeIfAbsent(player.getUniqueId(), id -> new PlayerGuiSession());
    }

    public void clearSession(UUID playerId) {
        sessions.remove(playerId);
    }

    // ---------------------------------------------------------------
    // Main menu
    // ---------------------------------------------------------------

    public void openMainMenu(Player player) {
        Component title = TextUtil.parse(config.guiTitleMainMenu());
        ShopInventoryHolder holder = ShopInventoryHolder.mainMenu();
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        bind(holder, inv);

        if (config.mainMenuDecorativeBorder()) {
            Material border = parseMaterialOr(config.mainMenuBorderMaterial(), Material.BLACK_STAINED_GLASS_PANE);
            ItemStack pane = items.decorativePane(border);
            for (int i = 0; i < inv.getSize(); i++) {
                inv.setItem(i, pane);
            }
        }

        List<Integer> slots = config.mainMenuCategorySlots();
        List<ShopCategory> categories = registry.categoriesOrdered();
        for (int i = 0; i < categories.size() && i < slots.size(); i++) {
            inv.setItem(slots.get(i), items.categoryIcon(categories.get(i)));
        }

        inv.setItem(config.mainMenuCloseSlot(), items.controlButton(Material.BARRIER, "<red>Close", GuiItemFactory.ACTION_CLOSE));
        inv.setItem(config.mainMenuBalanceSlot(), items.controlButton(Material.SUNFLOWER,
                "<gold>Balance: <white>" + economy.format(economy.getBalance(player)), GuiItemFactory.ACTION_NONE));

        player.openInventory(inv);
    }

    // ---------------------------------------------------------------
    // Category / search (shared rendering logic — a search is just a
    // virtual "category" whose item list comes from a query instead of
    // a category id)
    // ---------------------------------------------------------------

    public void openCategory(Player player, String categoryId, int page) {
        ShopCategory category = registry.category(categoryId).orElse(null);
        List<ShopItem> categoryItems = registry.itemsInCategory(categoryId);
        String title = category != null
                ? config.guiTitleCategory().replace("{category}", category.displayName())
                : config.guiTitleCategory().replace("{category}", categoryId);
        renderPaged(player, ShopInventoryHolder.category(categoryId, page), title, categoryItems, page);
    }

    public void openSearch(Player player, String query, int page) {
        List<ShopItem> results = registry.search(query);
        String title = config.guiTitleSearch().replace("{query}", query);
        renderPaged(player, ShopInventoryHolder.search(query, page), title, results, page);
    }

    private void renderPaged(Player player, ShopInventoryHolder holder, String rawTitle, List<ShopItem> fullList, int requestedPage) {
        int perPage = Math.max(1, config.categoryItemsPerPage());
        int totalPages = Math.max(1, (int) Math.ceil(fullList.size() / (double) perPage));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        holder.setPageInternal(page);

        Inventory inv = Bukkit.createInventory(holder, 54, TextUtil.parse(rawTitle));
        bind(holder, inv);

        int start = page * perPage;
        int end = Math.min(fullList.size(), start + perPage);
        PlayerGuiSession playerSession = session(player);

        for (int i = start; i < end; i++) {
            ShopItem item = fullList.get(i);
            inv.setItem(i - start, buildItemIcon(player, item, playerSession));
        }

        placeControlBar(inv, page, totalPages);
        player.openInventory(inv);
    }

    private ItemStack buildItemIcon(Player player, ShopItem item, PlayerGuiSession playerSession) {
        String buyStr = economy.format(item.buyPrice());
        String sellStr = economy.format(item.sellPrice());
        Integer currentStock = null;
        Integer maxStock = null;
        if (item.hasLimitedStock()) {
            currentStock = stock.currentStock(item.id()).orElse(0);
            maxStock = item.stockConfig().map(ShopItem.StockConfig::max).orElse(0);
        }
        return items.shopItemIcon(item, buyStr, sellStr, playerSession.quantityMode(), currentStock, maxStock);
    }

    private void placeControlBar(Inventory inv, int page, int totalPages) {
        int backSlot = config.controlBarSlot("back-slot", 45);
        int prevSlot = config.controlBarSlot("prev-page-slot", 46);
        int qtySlot = config.controlBarSlot("quantity-toggle-slot", 47);
        int searchSlot = config.controlBarSlot("search-slot", 48);
        int pageSlot = config.controlBarSlot("page-indicator-slot", 49);
        int sellHandSlot = config.controlBarSlot("sellhand-slot", 50);
        int sellAllSlot = config.controlBarSlot("sellall-slot", 51);
        int nextSlot = config.controlBarSlot("next-page-slot", 52);
        int closeSlot = config.controlBarSlot("close-slot", 53);

        inv.setItem(backSlot, items.controlButton(Material.ARROW, "<yellow>« Back to Categories", GuiItemFactory.ACTION_BACK));
        if (page > 0) {
            inv.setItem(prevSlot, items.controlButton(Material.ARROW, "<yellow>« Previous Page", GuiItemFactory.ACTION_PREV_PAGE));
        }
        inv.setItem(qtySlot, items.controlButton(Material.PAPER, "<yellow>Quantity Mode", GuiItemFactory.ACTION_QUANTITY_TOGGLE));
        inv.setItem(searchSlot, items.controlButton(Material.COMPASS, "<yellow>Search", GuiItemFactory.ACTION_SEARCH_PROMPT));
        inv.setItem(pageSlot, items.controlButton(Material.BOOK, "<gray>Page " + (page + 1) + "/" + totalPages, GuiItemFactory.ACTION_NONE));
        inv.setItem(sellHandSlot, items.controlButton(Material.IRON_INGOT, "<gold>Sell Item In Hand", GuiItemFactory.ACTION_SELLHAND));
        inv.setItem(sellAllSlot, items.controlButton(Material.GOLD_INGOT, "<gold>Sell All Sellable Items", GuiItemFactory.ACTION_SELLALL));
        if (page < totalPages - 1) {
            inv.setItem(nextSlot, items.controlButton(Material.ARROW, "<yellow>Next Page »", GuiItemFactory.ACTION_NEXT_PAGE));
        }
        inv.setItem(closeSlot, items.controlButton(Material.BARRIER, "<red>Close", GuiItemFactory.ACTION_CLOSE));
    }

    // ---------------------------------------------------------------
    // Confirmation screen
    // ---------------------------------------------------------------

    public void openConfirm(Player player, ShopInventoryHolder.PendingConfirmation pending, String summaryLine) {
        ShopInventoryHolder holder = ShopInventoryHolder.confirm(pending);
        Inventory inv = Bukkit.createInventory(holder, 27, TextUtil.parse(config.guiTitleConfirm()));
        bind(holder, inv);

        Material border = parseMaterialOr(config.mainMenuBorderMaterial(), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack pane = items.decorativePane(border);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, pane);
        }

        int confirmSlot = config.confirmSlot("confirm-slot", 11);
        int cancelSlot = config.confirmSlot("cancel-slot", 15);
        int infoSlot = config.confirmSlot("info-slot", 13);

        inv.setItem(confirmSlot, items.controlButton(Material.LIME_CONCRETE, "<green>Confirm", GuiItemFactory.ACTION_CONFIRM));
        inv.setItem(cancelSlot, items.controlButton(Material.RED_CONCRETE, "<red>Cancel", GuiItemFactory.ACTION_CANCEL));
        inv.setItem(infoSlot, items.controlButton(Material.PAPER, summaryLine, List.of(), GuiItemFactory.ACTION_NONE));

        player.openInventory(inv);
    }

    // ---------------------------------------------------------------

    private void bind(ShopInventoryHolder holder, Inventory inventory) {
        holder.bindInventory(inventory);
    }

    private Material parseMaterialOr(String name, Material fallback) {
        Material m = Material.matchMaterial(name);
        return m != null ? m : fallback;
    }

    public TransactionService transactions() {
        return transactions;
    }
}
