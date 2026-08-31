package com.aurorashop.commands;

import com.aurorashop.config.MessageManager;
import com.aurorashop.economy.EconomyService;
import com.aurorashop.model.ShopItem;
import com.aurorashop.model.TransactionResult;
import com.aurorashop.shop.ShopRegistry;
import com.aurorashop.transaction.SellExecutor;
import com.aurorashop.util.TextUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class SellHandCommand implements CommandExecutor {

    private final SellExecutor sellExecutor;
    private final ShopRegistry registry;
    private final MessageManager messages;
    private final EconomyService economy;

    public SellHandCommand(SellExecutor sellExecutor, ShopRegistry registry, MessageManager messages,
                            EconomyService economy) {
        this.sellExecutor = sellExecutor;
        this.registry = registry;
        this.messages = messages;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return true;
        }

        TransactionResult result = sellExecutor.sellHand(player);

        if (result.status() == TransactionResult.Status.CONFIGURATION_ERROR && result.quantity() == 0) {
            messages.send(player, "transaction.sold-nothing", TextUtil.map("item", "that"));
            return true;
        }

        ShopItem item = registry.item(result.itemId()).orElse(null);
        String itemName = item != null ? item.displayName() : result.itemId();

        switch (result.status()) {
            case SUCCESS -> messages.send(player, "transaction.sold", TextUtil.map(
                    "item", itemName, "amount", String.valueOf(result.quantity()), "price", economy.format(result.amount())));
            case SELL_DISABLED -> messages.send(player, "transaction.sell-disabled", TextUtil.map("item", itemName));
            case NOT_VANILLA_ITEM -> messages.send(player, "transaction.not-vanilla");
            case DAILY_LIMIT_REACHED -> messages.send(player, "transaction.daily-limit-sell", TextUtil.map(
                    "item", itemName, "used", "-", "limit", "-"));
            case TRANSACTION_CONFLICT -> messages.send(player, "transaction.already-processing");
            case RATE_LIMITED -> messages.send(player, "transaction.rate-limited");
            case ECONOMY_FAILURE -> messages.send(player, "transaction.economy-error");
            default -> messages.send(player, "transaction.sold-nothing", TextUtil.map("item", itemName));
        }
        return true;
    }
}
