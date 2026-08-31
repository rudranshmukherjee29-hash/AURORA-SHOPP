package com.aurorashop.transaction;

import com.aurorashop.model.ShopItem;
import com.aurorashop.model.TransactionResult;
import com.aurorashop.model.TransactionType;
import com.aurorashop.shop.ShopRegistry;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Both {@code /sellall}/{@code /sellinventory} and the shop GUI's
 * "Sell All" button need identical behaviour, and both
 * {@code /sellhand} and the GUI's "Sell Hand" button need identical
 * behaviour — this class is the one place that logic lives.
 */
public final class SellExecutor {

    private final ShopRegistry registry;
    private final TransactionService transactions;

    public SellExecutor(ShopRegistry registry, TransactionService transactions) {
        this.registry = registry;
        this.transactions = transactions;
    }

    /**
     * Sells whatever is currently in the player's main hand, using the
     * exact stack amount as the requested quantity. Returns
     * {@code TransactionResult.Status.CONFIGURATION_ERROR} with quantity 0
     * if the held item isn't a shop item at all (empty hand included) —
     * the caller can special-case that into a friendlier message.
     */
    public TransactionResult sellHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) {
            return TransactionResult.failure(TransactionResult.Status.CONFIGURATION_ERROR,
                    TransactionType.SELL, "none", 0);
        }
        List<String> ids = registry.itemIdsForMaterial(hand.getType());
        if (ids.isEmpty()) {
            return TransactionResult.failure(TransactionResult.Status.CONFIGURATION_ERROR,
                    TransactionType.SELL, hand.getType().name(), 0);
        }
        String itemId = ids.get(0);
        return transactions.sell(player, itemId, hand.getAmount(), false);
    }

    /**
     * Sells every sellable quantity of every distinct shop item currently
     * in the player's inventory. Each item id is a separate, fully
     * independent transaction (its own guard/limit/stock checks) — a
     * failure on one item (e.g. a daily limit) never prevents the others
     * from selling.
     */
    public SellAllSummary sellAllInventory(Player player) {
        Set<Material> materialsPresent = new HashSet<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null) {
                materialsPresent.add(stack.getType());
            }
        }

        int itemTypesSold = 0;
        BigDecimal totalPayout = BigDecimal.ZERO;
        for (Material material : materialsPresent) {
            for (String itemId : registry.itemIdsForMaterial(material)) {
                ShopItem item = registry.item(itemId).orElse(null);
                if (item == null || !item.sellEnabled()) {
                    continue;
                }
                TransactionResult result = transactions.sell(player, itemId, 0, true);
                if (result.isSuccess() && result.quantity() > 0) {
                    itemTypesSold++;
                    totalPayout = totalPayout.add(result.amount());
                }
            }
        }
        return new SellAllSummary(itemTypesSold, totalPayout);
    }

    public record SellAllSummary(int itemTypesSold, BigDecimal totalPayout) {
    }
}
