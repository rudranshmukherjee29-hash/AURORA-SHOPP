package com.aurorashop.commands;

import com.aurorashop.config.MessageManager;
import com.aurorashop.economy.EconomyService;
import com.aurorashop.transaction.SellExecutor;
import com.aurorashop.util.TextUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Backs both /sellall and /sellinventory — they are intentionally identical. */
public final class SellAllCommand implements CommandExecutor {

    private final SellExecutor sellExecutor;
    private final MessageManager messages;
    private final EconomyService economy;

    public SellAllCommand(SellExecutor sellExecutor, MessageManager messages, EconomyService economy) {
        this.sellExecutor = sellExecutor;
        this.messages = messages;
        this.economy = economy;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return true;
        }
        SellExecutor.SellAllSummary summary = sellExecutor.sellAllInventory(player);
        if (summary.itemTypesSold() == 0) {
            messages.send(player, "transaction.sold-all-nothing");
        } else {
            messages.send(player, "transaction.sold-all-summary", TextUtil.map(
                    "count", String.valueOf(summary.itemTypesSold()),
                    "price", economy.format(summary.totalPayout())));
        }
        return true;
    }
}
