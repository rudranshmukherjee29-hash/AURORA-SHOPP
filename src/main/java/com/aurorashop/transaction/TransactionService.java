package com.aurorashop.transaction;

import com.aurorashop.config.ConfigManager;
import com.aurorashop.economy.EconomyService;
import com.aurorashop.model.ShopItem;
import com.aurorashop.model.TransactionResult;
import com.aurorashop.model.TransactionResult.Status;
import com.aurorashop.model.TransactionType;
import com.aurorashop.shop.ShopRegistry;
import com.aurorashop.stats.StatisticsService;
import com.aurorashop.stock.StockService;
import com.aurorashop.util.InventoryUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implements the buy and sell sequences from the design brief exactly:
 * every numbered step in section 9 ("Transaction Requirements") has a
 * corresponding, explicitly-commented block below. Nothing about a
 * transaction's outcome is ever inferred — every branch returns a
 * concrete {@link TransactionResult}.
 * <p>
 * Threading: this class assumes it is always called from the thread that
 * owns the player (main thread on regular Paper, the player's region
 * thread on Folia) — i.e. from an event handler or a command executor.
 * All Vault/inventory calls here are therefore synchronous and safe;
 * only statistics/stock persistence hops to another thread, well after
 * the transaction has already concluded.
 */
public final class TransactionService {

    private final JavaPlugin plugin;
    private final ShopRegistry registry;
    private final ConfigManager config;
    private final EconomyService economy;
    private final StockService stock;
    private final StatisticsService stats;
    private final TransactionGuard guard;

    public TransactionService(JavaPlugin plugin, ShopRegistry registry, ConfigManager config,
                               EconomyService economy, StockService stock, StatisticsService stats,
                               TransactionGuard guard) {
        this.plugin = plugin;
        this.registry = registry;
        this.config = config;
        this.economy = economy;
        this.stock = stock;
        this.stats = stats;
        this.guard = guard;
    }

    // =================================================================
    //  BUY
    // =================================================================

    public TransactionResult buy(Player player, String itemId, long requestedQuantity) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();

        // Step 1 (part A): anti packet-spam throttle.
        if (guard.isRateLimited(playerId, config.rateLimitMillis())) {
            return fail(TransactionType.BUY, itemId, requestedQuantity, playerId, playerName, Status.RATE_LIMITED);
        }
        // Step 1 (part B): validate transaction state — acquire the single-flight guard.
        if (!guard.tryAcquire(playerId)) {
            return fail(TransactionType.BUY, itemId, requestedQuantity, playerId, playerName, Status.TRANSACTION_CONFLICT);
        }
        guard.markTransactionTime(playerId);

        boolean bypassLimits = player.hasPermission(config.bypassLimitsPermission());
        boolean dailyLimitReserved = false;
        boolean stockReserved = false;
        boolean committed = false;

        try {
            // Step 2: validate requested quantity.
            if (requestedQuantity <= 0 || requestedQuantity > config.maxTransactionSize()) {
                return fail(TransactionType.BUY, itemId, requestedQuantity, playerId, playerName, Status.INVALID_QUANTITY);
            }

            // Step 3: validate item configuration.
            ShopItem item = registry.item(itemId).orElse(null);
            if (item == null) {
                return fail(TransactionType.BUY, itemId, requestedQuantity, playerId, playerName, Status.CONFIGURATION_ERROR);
            }
            if (!item.buyEnabled()) {
                return fail(TransactionType.BUY, itemId, requestedQuantity, playerId, playerName, Status.BUY_DISABLED);
            }

            // Daily purchase limit (reserve now; released in `finally` unless we commit).
            if (!bypassLimits && item.dailyBuyLimit().isPresent()) {
                int limit = item.dailyBuyLimit().get();
                if (!stock.tryConsumeDailyLimit(playerId, itemId, true, requestedQuantity, limit)) {
                    return fail(TransactionType.BUY, itemId, requestedQuantity, playerId, playerName, Status.DAILY_LIMIT_REACHED);
                }
                dailyLimitReserved = true;
            }

            // Step 4: calculate exact price safely (BigDecimal, never floating point).
            BigDecimal totalPrice = item.totalBuyPrice(requestedQuantity);

            // Step 5: validate + atomically reserve stock, if this item has a limited stock pool.
            if (!bypassLimits && item.hasLimitedStock()) {
                if (!stock.tryReserveStock(itemId, requestedQuantity)) {
                    return fail(TransactionType.BUY, itemId, requestedQuantity, playerId, playerName, Status.OUT_OF_STOCK);
                }
                stockReserved = true;
            }

            // Step 6: verify player balance.
            if (!economy.has(player, totalPrice)) {
                return fail(TransactionType.BUY, itemId, requestedQuantity, playerId, playerName, Status.INSUFFICIENT_FUNDS);
            }

            // Inventory capacity is checked BEFORE any money moves, so a full inventory
            // never results in a withdrawal we'd then have to compensate for.
            long freeCapacity = InventoryUtil.freeCapacityFor(player.getInventory(), item.material());
            if (freeCapacity < requestedQuantity) {
                return fail(TransactionType.BUY, itemId, requestedQuantity, playerId, playerName, Status.INVENTORY_FULL);
            }

            // Steps 7-8: withdraw through Vault and verify success.
            if (!economy.withdraw(player, totalPrice)) {
                return fail(TransactionType.BUY, itemId, requestedQuantity, playerId, playerName, Status.ECONOMY_FAILURE);
            }

            // Steps 10-12: give the exact configured amount and verify it was fully delivered.
            boolean delivered = giveExactAmount(player, item.material(), requestedQuantity);
            if (!delivered) {
                // Compensation: refund the withdrawal since no items actually reached the player.
                boolean refunded = economy.deposit(player, totalPrice);
                if (!refunded) {
                    plugin.getLogger().severe("CRITICAL: withdrew " + totalPrice + " from " + playerName
                            + " for " + requestedQuantity + "x " + itemId + " but delivery AND refund both failed. "
                            + "Manual balance correction is required for player " + playerId + ".");
                }
                return fail(TransactionType.BUY, itemId, requestedQuantity, playerId, playerName, Status.INVENTORY_FULL);
            }

            // Step 13: record transaction.
            stats.recordSuccess(TransactionType.BUY, itemId, requestedQuantity, totalPrice, playerId, playerName);
            committed = true;
            return TransactionResult.success(TransactionType.BUY, itemId, requestedQuantity, totalPrice);

        } finally {
            // Step 14: release transaction state — including rolling back any reservation
            // that didn't end up backing a completed purchase.
            if (!committed) {
                if (stockReserved) {
                    stock.releaseStock(itemId, requestedQuantity);
                }
                if (dailyLimitReserved) {
                    stock.releaseDailyLimit(playerId, itemId, true, requestedQuantity);
                }
            }
            guard.release(playerId);
        }
    }

    // =================================================================
    //  SELL
    // =================================================================

    public TransactionResult sell(Player player, String itemId, long requestedQuantity, boolean sellAll) {
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();

        if (guard.isRateLimited(playerId, config.rateLimitMillis())) {
            return fail(TransactionType.SELL, itemId, requestedQuantity, playerId, playerName, Status.RATE_LIMITED);
        }
        if (!guard.tryAcquire(playerId)) {
            return fail(TransactionType.SELL, itemId, requestedQuantity, playerId, playerName, Status.TRANSACTION_CONFLICT);
        }
        guard.markTransactionTime(playerId);

        boolean bypassLimits = player.hasPermission(config.bypassLimitsPermission());
        boolean dailyLimitReserved = false;
        long dailyLimitReservedAmount = 0;
        boolean committed = false;

        try {
            // Step 2: validate item and quantity.
            ShopItem item = registry.item(itemId).orElse(null);
            if (item == null) {
                return fail(TransactionType.SELL, itemId, requestedQuantity, playerId, playerName, Status.CONFIGURATION_ERROR);
            }
            if (!item.sellEnabled()) {
                return fail(TransactionType.SELL, itemId, requestedQuantity, playerId, playerName, Status.SELL_DISABLED);
            }
            if (!sellAll && (requestedQuantity <= 0 || requestedQuantity > config.maxTransactionSize())) {
                return fail(TransactionType.SELL, itemId, requestedQuantity, playerId, playerName, Status.INVALID_QUANTITY);
            }

            boolean requireVanilla = config.requireVanillaOnly();

            // Step 3: count exact matching (and, if configured, unmodified-vanilla-only) items.
            long sellableAvailable = InventoryUtil.countSellable(player.getInventory(), item.material(), requireVanilla);
            long quantity = sellAll ? Math.min(sellableAvailable, config.maxTransactionSize()) : requestedQuantity;

            if (quantity <= 0) {
                // Distinguish "you have none at all" from "you have some, but they're modified".
                if (requireVanilla) {
                    long rawAvailable = InventoryUtil.countSellable(player.getInventory(), item.material(), false);
                    if (rawAvailable > 0) {
                        return fail(TransactionType.SELL, itemId, requestedQuantity, playerId, playerName, Status.NOT_VANILLA_ITEM);
                    }
                }
                return fail(TransactionType.SELL, itemId, requestedQuantity, playerId, playerName, Status.INSUFFICIENT_ITEMS);
            }
            if (!sellAll && sellableAvailable < quantity) {
                return fail(TransactionType.SELL, itemId, requestedQuantity, playerId, playerName, Status.INSUFFICIENT_ITEMS);
            }

            // Daily sell limit — clamp sell-all down to the remaining allowance rather than
            // failing outright, since "sell everything you can" is the expected semantics.
            if (!bypassLimits && item.dailySellLimit().isPresent()) {
                int limit = item.dailySellLimit().get();
                int alreadyUsed = stock.dailyUsageUsed(playerId, itemId, false);
                long remaining = Math.max(0, limit - alreadyUsed);
                if (sellAll) {
                    quantity = Math.min(quantity, remaining);
                    if (quantity <= 0) {
                        return fail(TransactionType.SELL, itemId, requestedQuantity, playerId, playerName, Status.DAILY_LIMIT_REACHED);
                    }
                } else if (quantity > remaining) {
                    return fail(TransactionType.SELL, itemId, requestedQuantity, playerId, playerName, Status.DAILY_LIMIT_REACHED);
                }
                if (!stock.tryConsumeDailyLimit(playerId, itemId, false, quantity, limit)) {
                    return fail(TransactionType.SELL, itemId, requestedQuantity, playerId, playerName, Status.DAILY_LIMIT_REACHED);
                }
                dailyLimitReserved = true;
                dailyLimitReservedAmount = quantity;
            }

            BigDecimal payout = item.totalSellPrice(quantity);

            // Steps 4-5: remove exactly `quantity` and verify the removal matched.
            long actuallyRemoved = InventoryUtil.removeExact(player.getInventory(), item.material(), quantity, requireVanilla);
            if (actuallyRemoved != quantity) {
                // The inventory changed between our count and our removal (e.g. another
                // plugin/event acted concurrently within this same tick). Put back whatever
                // we did remove and abort — never settle for a partial, unverified sale.
                if (actuallyRemoved > 0) {
                    player.getInventory().addItem(new ItemStack(item.material(), safeInt(actuallyRemoved)));
                }
                return fail(TransactionType.SELL, itemId, requestedQuantity, playerId, playerName, Status.INSUFFICIENT_ITEMS);
            }

            // Steps 6-7: deposit through Vault and verify success.
            boolean deposited = economy.deposit(player, payout);
            if (!deposited) {
                // Compensation: give the removed items back since no payout was received.
                player.getInventory().addItem(new ItemStack(item.material(), safeInt(actuallyRemoved)));
                return fail(TransactionType.SELL, itemId, requestedQuantity, playerId, playerName, Status.ECONOMY_FAILURE);
            }

            stock.restockOnSell(itemId, quantity);

            // Step 8: record transaction.
            stats.recordSuccess(TransactionType.SELL, itemId, quantity, payout, playerId, playerName);
            committed = true;
            return TransactionResult.success(TransactionType.SELL, itemId, quantity, payout);

        } finally {
            // Step 9: safely handle failure — release any daily-limit reservation that
            // didn't end up backing a completed sale.
            if (!committed && dailyLimitReserved) {
                stock.releaseDailyLimit(playerId, itemId, false, dailyLimitReservedAmount);
            }
            guard.release(playerId);
        }
    }

    // =================================================================
    //  Helpers
    // =================================================================

    /**
     * The largest quantity of {@code item} that {@code player} could buy
     * right now, considering balance, inventory space, the global
     * transaction cap, remaining stock, and remaining daily limit. Used to
     * size "Buy max affordable" clicks; the actual {@link #buy} call still
     * independently re-validates everything, so this is a UX convenience,
     * never a trust boundary.
     */
    public long computeMaxAffordableQuantity(Player player, ShopItem item) {
        if (!item.buyEnabled() || item.buyPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        boolean bypassLimits = player.hasPermission(config.bypassLimitsPermission());

        BigDecimal balance = economy.getBalance(player);
        long byBalance = balance.divideToIntegralValue(item.buyPrice()).setScale(0, RoundingMode.DOWN).longValue();
        long byCapacity = InventoryUtil.freeCapacityFor(player.getInventory(), item.material());
        long byMaxTx = config.maxTransactionSize();

        long byStock = Long.MAX_VALUE;
        if (!bypassLimits && item.hasLimitedStock()) {
            byStock = stock.currentStock(item.id()).orElse(0);
        }

        long byDailyLimit = Long.MAX_VALUE;
        if (!bypassLimits && item.dailyBuyLimit().isPresent()) {
            int used = stock.dailyUsageUsed(player.getUniqueId(), item.id(), true);
            byDailyLimit = Math.max(0, item.dailyBuyLimit().get() - used);
        }

        return List.of(byBalance, byCapacity, byMaxTx, byStock, byDailyLimit).stream()
                .mapToLong(Long::longValue).min().orElse(0);
    }

    /** How many sellable units of this item the player is currently holding (respecting vanilla-only filtering). */
    public long computeSellableAmount(Player player, ShopItem item) {
        return InventoryUtil.countSellable(player.getInventory(), item.material(), config.requireVanillaOnly());
    }

    /**
     * Gives exactly {@code quantity} of {@code material} to the player, split into
     * max-stack-sized chunks. If any chunk doesn't fully fit — which should never
     * happen since callers check capacity first — every chunk given so far in this
     * call is rolled back so delivery is all-or-nothing.
     */
    private boolean giveExactAmount(Player player, Material material, long quantity) {
        List<Integer> givenChunks = new ArrayList<>();
        long remaining = quantity;
        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, material.getMaxStackSize());
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, chunk));
            int notAdded = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            int actuallyAdded = chunk - notAdded;
            if (notAdded > 0) {
                // Roll back this partial chunk and every prior chunk from this call.
                if (actuallyAdded > 0) {
                    InventoryUtil.removeExact(player.getInventory(), material, actuallyAdded, false);
                }
                for (int given : givenChunks) {
                    InventoryUtil.removeExact(player.getInventory(), material, given, false);
                }
                return false;
            }
            givenChunks.add(chunk);
            remaining -= chunk;
        }
        return true;
    }

    private int safeInt(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private TransactionResult fail(TransactionType type, String itemId, long quantity, UUID playerId,
                                    String playerName, Status status) {
        stats.recordFailure(type, itemId, quantity, playerId, playerName, status.name());
        return TransactionResult.failure(status, type, itemId, quantity);
    }
}
