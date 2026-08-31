package com.aurorashop.gui;

import com.aurorashop.config.ConfigManager;
import com.aurorashop.config.MessageManager;
import com.aurorashop.economy.EconomyService;
import com.aurorashop.model.ShopItem;
import com.aurorashop.model.TransactionResult;
import com.aurorashop.model.TransactionType;
import com.aurorashop.shop.ShopRegistry;
import com.aurorashop.stock.StockService;
import com.aurorashop.transaction.SellExecutor;
import com.aurorashop.transaction.TransactionService;
import com.aurorashop.util.TextUtil;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.math.BigDecimal;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every click and drag inside an AuroraShop inventory passes through here.
 * The governing rule (design brief section 9, "Inventory Security"): we
 * cancel the event unconditionally the moment we recognize the top
 * inventory as ours, THEN decide what to do — we never let default Bukkit
 * click behaviour (item movement, hotbar swap, collect-to-cursor, drag)
 * touch a shop inventory at all, regardless of click type.
 * <p>
 * Every action is resolved purely from the clicked icon's
 * PersistentDataContainer tags (see {@link GuiKeys}), never from its
 * display name, lore, or slot position.
 */
public final class ShopGuiListener implements Listener {

    private final Logger logger;
    private final GuiKeys keys;
    private final ShopGuiManager guiManager;
    private final ShopRegistry registry;
    private final TransactionService transactions;
    private final SellExecutor sellExecutor;
    private final EconomyService economy;
    private final StockService stock;
    private final ConfigManager config;
    private final MessageManager messages;

    public ShopGuiListener(Logger logger, GuiKeys keys, ShopGuiManager guiManager, ShopRegistry registry,
                            TransactionService transactions, SellExecutor sellExecutor, EconomyService economy,
                            StockService stock, ConfigManager config, MessageManager messages) {
        this.logger = logger;
        this.keys = keys;
        this.guiManager = guiManager;
        this.registry = registry;
        this.transactions = transactions;
        this.sellExecutor = sellExecutor;
        this.economy = economy;
        this.stock = stock;
        this.config = config;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopInventoryHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShopInventoryHolder holder)) {
            return; // not our GUI — touch nothing
        }
        // Cancel unconditionally: this blocks shift-clicks from the player's own
        // inventory, number-key hotbar swaps, double-click collection, drag-drop,
        // and offhand swap attempts against our icons, all in one place.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return; // click landed on the player's own inventory while shop was open
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            return;
        }
        PersistentDataContainer pdc = clicked.getItemMeta().getPersistentDataContainer();
        String action = pdc.get(keys.action, PersistentDataType.STRING);
        if (action == null) {
            return;
        }

        try {
            switch (holder.type()) {
                case MAIN_MENU -> handleMainMenuClick(player, action);
                case CATEGORY, SEARCH -> handleBrowseClick(player, holder, action, pdc, event.getClick());
                case CONFIRM -> handleConfirmClick(player, holder, action);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unexpected error handling AuroraShop GUI click for " + player.getName(), e);
            messages.send(player, "transaction.config-error");
        }
    }

    // ---------------------------------------------------------------

    private void handleMainMenuClick(Player player, String action) {
        if (action.equals(GuiItemFactory.ACTION_CLOSE)) {
            playSound(player, "click");
            player.closeInventory();
        } else if (action.startsWith(GuiItemFactory.ACTION_CATEGORY_PREFIX)) {
            String categoryId = action.substring(GuiItemFactory.ACTION_CATEGORY_PREFIX.length());
            playSound(player, "click");
            guiManager.openCategory(player, categoryId, 0);
        }
    }

    private void handleBrowseClick(Player player, ShopInventoryHolder holder, String action,
                                    PersistentDataContainer pdc, ClickType click) {
        switch (action) {
            case GuiItemFactory.ACTION_BACK -> {
                playSound(player, "click");
                guiManager.openMainMenu(player);
            }
            case GuiItemFactory.ACTION_CLOSE -> {
                playSound(player, "click");
                player.closeInventory();
            }
            case GuiItemFactory.ACTION_PREV_PAGE -> {
                playSound(player, "page-turn");
                refreshCurrentScreen(player, holder, holder.page() - 1);
            }
            case GuiItemFactory.ACTION_NEXT_PAGE -> {
                playSound(player, "page-turn");
                refreshCurrentScreen(player, holder, holder.page() + 1);
            }
            case GuiItemFactory.ACTION_QUANTITY_TOGGLE -> {
                PlayerGuiSession session = guiManager.session(player);
                session.setQuantityMode(session.quantityMode().next());
                playSound(player, "click");
                refreshCurrentScreen(player, holder, holder.page());
            }
            case GuiItemFactory.ACTION_SEARCH_PROMPT -> {
                player.closeInventory();
                messages.send(player, "shop.search-prompt");
            }
            case GuiItemFactory.ACTION_SELLHAND -> {
                player.closeInventory();
                TransactionResult result = sellExecutor.sellHand(player);
                reportSellHandResult(player, result);
            }
            case GuiItemFactory.ACTION_SELLALL -> {
                player.closeInventory();
                SellExecutor.SellAllSummary summary = sellExecutor.sellAllInventory(player);
                reportSellAllSummary(player, summary);
            }
            case GuiItemFactory.ACTION_ITEM -> handleItemClick(player, holder, pdc, click);
            default -> {
            }
        }
    }

    private void handleItemClick(Player player, ShopInventoryHolder holder, PersistentDataContainer pdc, ClickType click) {
        String itemId = pdc.get(keys.itemId, PersistentDataType.STRING);
        if (itemId == null) {
            return;
        }
        ShopItem item = registry.item(itemId).orElse(null);
        if (item == null) {
            messages.send(player, "transaction.config-error");
            return;
        }

        switch (click) {
            case LEFT -> attemptBuy(player, holder, item, quantityForPlainClick(player, item));
            case SHIFT_LEFT -> attemptBuy(player, holder, item, 64L);
            case RIGHT -> attemptSell(player, holder, item, 1L, false);
            case SHIFT_RIGHT -> attemptSell(player, holder, item, 0L, true);
            default -> {
                // Middle click, number keys, double-click, drop, offhand-swap: already
                // cancelled above and intentionally not mapped to any shop action.
            }
        }
    }

    private long quantityForPlainClick(Player player, ShopItem item) {
        QuantityMode mode = guiManager.session(player).quantityMode();
        if (mode.isMax()) {
            return transactions.computeMaxAffordableQuantity(player, item);
        }
        return mode.fixedAmount();
    }

    // ---------------------------------------------------------------

    private void attemptBuy(Player player, ShopInventoryHolder holder, ShopItem item, long quantity) {
        if (!item.buyEnabled()) {
            playSound(player, "error");
            messages.send(player, "transaction.buy-disabled", TextUtil.map("item", item.displayName()));
            return;
        }
        if (quantity <= 0) {
            playSound(player, "error");
            messages.send(player, "transaction.invalid-quantity");
            return;
        }
        BigDecimal total = item.totalBuyPrice(quantity);
        if (total.compareTo(config.confirmationThreshold()) >= 0) {
            openConfirmation(player, holder, TransactionType.BUY, item, quantity, false, total);
            return;
        }
        TransactionResult result = transactions.buy(player, item.id(), quantity);
        reportResult(player, result, item);
        refreshCurrentScreen(player, holder, holder.page());
    }

    private void attemptSell(Player player, ShopInventoryHolder holder, ShopItem item, long quantity, boolean sellAll) {
        if (!item.sellEnabled()) {
            playSound(player, "error");
            messages.send(player, "transaction.sell-disabled", TextUtil.map("item", item.displayName()));
            return;
        }
        long available = transactions.computeSellableAmount(player, item);
        long checkQuantity = sellAll ? available : quantity;
        if (checkQuantity <= 0) {
            playSound(player, "error");
            messages.send(player, "transaction.sold-nothing", TextUtil.map("item", item.displayName()));
            return;
        }
        BigDecimal payoutEstimate = item.totalSellPrice(checkQuantity);
        if (payoutEstimate.compareTo(config.confirmationThreshold()) >= 0) {
            openConfirmation(player, holder, TransactionType.SELL, item, checkQuantity, sellAll, payoutEstimate);
            return;
        }
        TransactionResult result = transactions.sell(player, item.id(), quantity, sellAll);
        reportResult(player, result, item);
        refreshCurrentScreen(player, holder, holder.page());
    }

    private void openConfirmation(Player player, ShopInventoryHolder holder, TransactionType type, ShopItem item,
                                   long quantity, boolean sellAll, BigDecimal amount) {
        String returnCategoryId = holder.type() == ShopInventoryHolder.Type.CATEGORY ? holder.categoryId() : null;
        String returnSearchQuery = holder.type() == ShopInventoryHolder.Type.SEARCH ? holder.searchQuery() : null;
        ShopInventoryHolder.PendingConfirmation pending = new ShopInventoryHolder.PendingConfirmation(
                type, item.id(), quantity, sellAll, returnCategoryId, returnSearchQuery, holder.page());
        String verb = type == TransactionType.BUY ? "Buy" : "Sell";
        String summary = "<gold>" + verb + " " + quantity + "x " + item.displayName() + " — " + economy.format(amount);
        playSound(player, "click");
        guiManager.openConfirm(player, pending, summary);
    }

    private void handleConfirmClick(Player player, ShopInventoryHolder holder, String action) {
        ShopInventoryHolder.PendingConfirmation pending = holder.pendingConfirmation();
        if (pending == null) {
            player.closeInventory();
            return;
        }
        if (action.equals(GuiItemFactory.ACTION_CONFIRM)) {
            ShopItem item = registry.item(pending.itemId()).orElse(null);
            if (item == null) {
                messages.send(player, "transaction.config-error");
                player.closeInventory();
                return;
            }
            TransactionResult result = pending.type() == TransactionType.BUY
                    ? transactions.buy(player, pending.itemId(), pending.quantity())
                    : transactions.sell(player, pending.itemId(), pending.quantity(), pending.sellAll());
            reportResult(player, result, item);
            returnToOrigin(player, pending);
        } else if (action.equals(GuiItemFactory.ACTION_CANCEL)) {
            playSound(player, "click");
            messages.send(player, "transaction.cancelled");
            returnToOrigin(player, pending);
        }
    }

    private void returnToOrigin(Player player, ShopInventoryHolder.PendingConfirmation pending) {
        if (pending.returnCategoryId() != null) {
            guiManager.openCategory(player, pending.returnCategoryId(), pending.returnPage());
        } else if (pending.returnSearchQuery() != null) {
            guiManager.openSearch(player, pending.returnSearchQuery(), pending.returnPage());
        } else {
            guiManager.openMainMenu(player);
        }
    }

    private void refreshCurrentScreen(Player player, ShopInventoryHolder holder, int page) {
        switch (holder.type()) {
            case CATEGORY -> guiManager.openCategory(player, holder.categoryId(), page);
            case SEARCH -> guiManager.openSearch(player, holder.searchQuery(), page);
            case MAIN_MENU -> guiManager.openMainMenu(player);
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------

    private void reportResult(Player player, TransactionResult result, ShopItem item) {
        Map<String, String> base = TextUtil.map(
                "item", item.displayName(),
                "amount", String.valueOf(result.quantity()),
                "price", economy.format(result.amount())
        );

        switch (result.status()) {
            case SUCCESS -> {
                playSound(player, result.type() == TransactionType.BUY ? "buy" : "sell");
                messages.send(player, result.type() == TransactionType.BUY ? "transaction.bought" : "transaction.sold", base);
            }
            case INVALID_QUANTITY -> {
                playSound(player, "error");
                messages.send(player, "transaction.invalid-quantity");
            }
            case INSUFFICIENT_FUNDS -> {
                playSound(player, "error");
                messages.send(player, "transaction.insufficient-funds", TextUtil.map(
                        "price", economy.format(item.totalBuyPrice(Math.max(1, result.quantity()))),
                        "balance", economy.format(economy.getBalance(player))));
            }
            case INSUFFICIENT_ITEMS -> {
                playSound(player, "error");
                long have = transactions.computeSellableAmount(player, item);
                messages.send(player, "transaction.insufficient-items", TextUtil.map(
                        "have", String.valueOf(have), "amount", String.valueOf(Math.max(1, result.quantity()))));
            }
            case INVENTORY_FULL -> {
                playSound(player, "error");
                messages.send(player, "transaction.inventory-full", base);
            }
            case OUT_OF_STOCK -> {
                playSound(player, "error");
                int remaining = stock.currentStock(item.id()).orElse(0);
                messages.send(player, "transaction.out-of-stock", TextUtil.map(
                        "item", item.displayName(), "remaining", String.valueOf(remaining)));
            }
            case ECONOMY_FAILURE -> {
                playSound(player, "error");
                messages.send(player, "transaction.economy-error");
            }
            case TRANSACTION_CONFLICT -> {
                playSound(player, "error");
                messages.send(player, "transaction.already-processing");
            }
            case CONFIGURATION_ERROR -> {
                playSound(player, "error");
                messages.send(player, "transaction.config-error");
            }
            case DAILY_LIMIT_REACHED -> {
                playSound(player, "error");
                int used = stock.dailyUsageUsed(player.getUniqueId(), item.id(),
                        result.type() == TransactionType.BUY);
                int limit = result.type() == TransactionType.BUY
                        ? item.dailyBuyLimit().orElse(0)
                        : item.dailySellLimit().orElse(0);
                String key = result.type() == TransactionType.BUY ? "transaction.daily-limit-buy" : "transaction.daily-limit-sell";
                messages.send(player, key, TextUtil.map("item", item.displayName(),
                        "used", String.valueOf(used), "limit", String.valueOf(limit)));
            }
            case NOT_VANILLA_ITEM -> {
                playSound(player, "error");
                messages.send(player, "transaction.not-vanilla");
            }
            case BUY_DISABLED -> {
                playSound(player, "error");
                messages.send(player, "transaction.buy-disabled", TextUtil.map("item", item.displayName()));
            }
            case SELL_DISABLED -> {
                playSound(player, "error");
                messages.send(player, "transaction.sell-disabled", TextUtil.map("item", item.displayName()));
            }
            case RATE_LIMITED -> {
                playSound(player, "error");
                messages.send(player, "transaction.rate-limited");
            }
        }
    }

    private void reportSellHandResult(Player player, TransactionResult result) {
        if (result.status() == TransactionResult.Status.CONFIGURATION_ERROR && result.quantity() == 0) {
            messages.send(player, "transaction.sold-nothing", TextUtil.map("item", "that"));
            return;
        }
        ShopItem item = registry.item(result.itemId()).orElse(null);
        if (item != null) {
            reportResult(player, result, item);
        }
    }

    private void reportSellAllSummary(Player player, SellExecutor.SellAllSummary summary) {
        if (summary.itemTypesSold() == 0) {
            playSound(player, "error");
            messages.send(player, "transaction.sold-all-nothing");
            return;
        }
        playSound(player, "sell");
        messages.send(player, "transaction.sold-all-summary", TextUtil.map(
                "count", String.valueOf(summary.itemTypesSold()),
                "price", economy.format(summary.totalPayout())));
    }

    private void playSound(Player player, String key) {
        if (!config.soundsEnabled()) {
            return;
        }
        try {
            Sound sound = Sound.valueOf(config.sound(key, key));
            player.playSound(player.getLocation(), sound, 0.7f, 1.0f);
        } catch (IllegalArgumentException ignored) {
            // Misconfigured sound name — silently skip rather than spam the console every click.
        }
    }
}
