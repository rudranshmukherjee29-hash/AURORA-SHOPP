package com.aurorashop.commands;

import com.aurorashop.ReloadResult;
import com.aurorashop.config.MessageManager;
import com.aurorashop.economy.EconomyService;
import com.aurorashop.model.ShopItem;
import com.aurorashop.shop.PriceValidator;
import com.aurorashop.shop.ShopRegistry;
import com.aurorashop.stats.ItemStatEntry;
import com.aurorashop.stats.StatisticsService;
import com.aurorashop.stock.StockService;
import com.aurorashop.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class ShopAdminCommand implements CommandExecutor, TabCompleter {

    private final Supplier<ReloadResult> reloadCallback;
    private final ShopRegistry registry;
    private final PriceValidator priceValidator;
    private final StockService stock;
    private final StatisticsService stats;
    private final EconomyService economy;
    private final MessageManager messages;
    private final Logger logger;

    public ShopAdminCommand(Supplier<ReloadResult> reloadCallback, ShopRegistry registry, PriceValidator priceValidator,
                             StockService stock, StatisticsService stats, EconomyService economy,
                             MessageManager messages, Logger logger) {
        this.reloadCallback = reloadCallback;
        this.registry = registry;
        this.priceValidator = priceValidator;
        this.stock = stock;
        this.stats = stats;
        this.economy = economy;
        this.messages = messages;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(TextUtil.parse("<yellow>/shopadmin <reload|stats|givebalance|audit|validateprices|restock>"));
            return true;
        }

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "reload" -> handleReload(sender);
            case "stats" -> handleStats(sender, args);
            case "givebalance" -> handleGiveBalance(sender, args);
            case "audit" -> handleAudit(sender, args);
            case "validateprices" -> handleValidatePrices(sender);
            case "restock" -> handleRestock(sender, args);
            default -> sender.sendMessage(TextUtil.parse("<yellow>/shopadmin <reload|stats|givebalance|audit|validateprices|restock>"));
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        ReloadResult result = reloadCallback.get();
        if (!result.success()) {
            sender.sendMessage(TextUtil.parse("<red>Reload failed — see console for details. The previous configuration is still active."));
            return;
        }
        messages.send(sender, "general.reloaded", TextUtil.map(
                "items", String.valueOf(result.itemCount()), "categories", String.valueOf(result.categoryCount())));
        if (!result.pricingWarnings().isEmpty()) {
            sender.sendMessage(TextUtil.parse("<red>" + result.pricingWarnings().size() + " pricing warning(s) — see console."));
        }
    }

    private void handleStats(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            String itemId = args[1];
            ShopItem item = registry.item(itemId).orElse(null);
            if (item == null) {
                messages.send(sender, "admin.stats-item-not-found", TextUtil.map("item", itemId));
                return;
            }
            var entryOpt = stats.itemStats(itemId);
            sender.sendMessage(TextUtil.parse("<gold><bold>Stats — " + item.displayName()));
            if (entryOpt.isEmpty()) {
                sender.sendMessage(TextUtil.parse("<gray>No transactions recorded yet."));
                return;
            }
            ItemStatEntry entry = entryOpt.get();
            sender.sendMessage(TextUtil.parse("<gray>Bought: <white>" + entry.boughtCount() + "  <gray>Spent: <white>" + economy.format(entry.spent())));
            sender.sendMessage(TextUtil.parse("<gray>Sold: <white>" + entry.soldCount() + "  <gray>Earned: <white>" + economy.format(entry.earned())));
            sender.sendMessage(TextUtil.parse("<gray>Transactions: <white>" + entry.transactionCount()));
            Integer currentStock = stock.currentStock(itemId).orElse(null);
            if (currentStock != null) {
                sender.sendMessage(TextUtil.parse("<gray>Current stock: <white>" + currentStock));
            }
            return;
        }

        messages.send(sender, "admin.stats-header");
        sender.sendMessage(TextUtil.parse("<gray>Total bought: <white>" + stats.totalBought()
                + "  <gray>Total sold: <white>" + stats.totalSold()));
        sender.sendMessage(TextUtil.parse("<gray>Total spent: <white>" + economy.format(stats.totalSpent())
                + "  <gray>Total earned: <white>" + economy.format(stats.totalEarned())));
        sender.sendMessage(TextUtil.parse("<gray>Transactions: <white>" + stats.totalTransactions()
                + "  <gray>Failed: <white>" + stats.failedTransactions()));

        sender.sendMessage(TextUtil.parse("<gold>Top bought:"));
        for (Map.Entry<String, ItemStatEntry> entry : stats.topBought(5)) {
            String name = registry.item(entry.getKey()).map(ShopItem::displayName).orElse(entry.getKey());
            sender.sendMessage(TextUtil.parse("  <gray>" + name + ": <white>" + entry.getValue().boughtCount()));
        }
        sender.sendMessage(TextUtil.parse("<gold>Top sold:"));
        for (Map.Entry<String, ItemStatEntry> entry : stats.topSold(5)) {
            String name = registry.item(entry.getKey()).map(ShopItem::displayName).orElse(entry.getKey());
            sender.sendMessage(TextUtil.parse("  <gray>" + name + ": <white>" + entry.getValue().soldCount()));
        }
    }

    private void handleGiveBalance(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.send(sender, "admin.givebalance-usage");
            return;
        }
        String playerName = args[1];
        BigDecimal amount;
        try {
            amount = new BigDecimal(args[2]);
        } catch (NumberFormatException e) {
            messages.send(sender, "admin.givebalance-failed", TextUtil.map("reason", "'" + args[2] + "' is not a valid amount"));
            return;
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            messages.send(sender, "admin.givebalance-failed", TextUtil.map("reason", "amount must be positive"));
            return;
        }

        // Avoid any blocking UUID lookup on the main thread: only resolve players who are
        // either online right now or already known locally (Paper's cached-only lookup).
        OfflinePlayer target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(playerName);
        }
        if (target == null) {
            messages.send(sender, "admin.player-not-found", TextUtil.map("player", playerName));
            return;
        }

        boolean success = economy.deposit(target, amount);
        if (success) {
            messages.send(sender, "admin.givebalance-success", TextUtil.map(
                    "amount", economy.format(amount), "player", playerName));
        } else {
            messages.send(sender, "admin.givebalance-failed", TextUtil.map("reason", "the economy provider rejected the deposit"));
        }
    }

    private void handleAudit(CommandSender sender, String[] args) {
        if (args.length < 2) {
            messages.send(sender, "admin.audit-usage");
            return;
        }
        ShopItem item = registry.item(args[1]).orElse(null);
        if (item == null) {
            messages.send(sender, "admin.audit-not-found", TextUtil.map("item", args[1]));
            return;
        }
        sender.sendMessage(TextUtil.parse("<gold><bold>Audit — " + item.displayName() + " <gray>(" + item.id() + ")"));
        sender.sendMessage(TextUtil.parse("<gray>Material: <white>" + item.material()
                + "  <gray>Category: <white>" + item.categoryId()));
        sender.sendMessage(TextUtil.parse("<gray>Buy: <white>" + (item.buyEnabled() ? economy.format(item.buyPrice()) : "disabled")
                + "  <gray>Sell: <white>" + (item.sellEnabled() ? economy.format(item.sellPrice()) : "disabled")));
        item.dailyBuyLimit().ifPresent(l -> sender.sendMessage(TextUtil.parse("<gray>Daily buy limit: <white>" + l)));
        item.dailySellLimit().ifPresent(l -> sender.sendMessage(TextUtil.parse("<gray>Daily sell limit: <white>" + l)));
        item.stockConfig().ifPresent(cfg -> sender.sendMessage(TextUtil.parse("<gray>Stock: <white>"
                + stock.currentStock(item.id()).orElse(cfg.initialCurrent()) + "/" + cfg.max()
                + " <gray>(auto-restock: " + cfg.automaticRestock() + ")")));
        item.conversions().ifPresent(conv -> {
            conv.smeltsFrom().ifPresent(s -> sender.sendMessage(TextUtil.parse("<gray>Smelts from: <white>" + s)));
            conv.compressesInto().ifPresent(c -> sender.sendMessage(TextUtil.parse(
                    "<gray>Compresses: <white>" + c.ratio() + "x this <-> 1x " + c.targetItemId())));
            conv.craftsFrom().ifPresent(c -> sender.sendMessage(TextUtil.parse(
                    "<gray>Crafts from: <white>" + c.components().stream()
                            .map(comp -> comp.amount() + "x " + comp.itemId())
                            .collect(Collectors.joining(", ")) + " -> " + c.outputAmount() + "x this")));
        });
    }

    private void handleValidatePrices(CommandSender sender) {
        List<String> problems = priceValidator.validate();
        if (problems.isEmpty()) {
            messages.send(sender, "admin.validate-none", TextUtil.map("count", String.valueOf(registry.allItems().size())));
            return;
        }
        messages.send(sender, "admin.validate-found", TextUtil.map("count", String.valueOf(problems.size())));
        logger.warning("=== AuroraShop price validation found " + problems.size() + " problem(s) ===");
        for (String problem : problems) {
            logger.warning(" - " + problem);
        }
    }

    private void handleRestock(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(TextUtil.parse("<yellow>/shopadmin restock <item> [amount|max]"));
            return;
        }
        ShopItem item = registry.item(args[1]).orElse(null);
        if (item == null) {
            messages.send(sender, "admin.audit-not-found", TextUtil.map("item", args[1]));
            return;
        }
        if (item.stockConfig().isEmpty()) {
            sender.sendMessage(TextUtil.parse("<red>" + item.displayName() + " does not use limited stock."));
            return;
        }
        if (args.length >= 3 && !args[2].equalsIgnoreCase("max")) {
            try {
                int amount = Integer.parseInt(args[2]);
                stock.restockBy(item.id(), amount);
                sender.sendMessage(TextUtil.parse("<green>Added " + amount + " stock to " + item.displayName() + "."));
                return;
            } catch (NumberFormatException e) {
                sender.sendMessage(TextUtil.parse("<red>'" + args[2] + "' is not a valid amount."));
                return;
            }
        }
        stock.restockToMax(item.id());
        sender.sendMessage(TextUtil.parse("<green>Restocked " + item.displayName() + " to its maximum."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("reload", "stats", "givebalance", "audit", "validateprices", "restock"), args[0]);
        }
        if (args.length == 2 && List.of("stats", "audit", "restock").contains(args[0].toLowerCase(java.util.Locale.ROOT))) {
            return filter(registry.allItems().stream().map(ShopItem::id).collect(Collectors.toList()), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("restock")) {
            return filter(List.of("max"), args[2]);
        }
        return new ArrayList<>();
    }

    private List<String> filter(List<String> options, String partial) {
        String lower = partial.toLowerCase(java.util.Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(java.util.Locale.ROOT).startsWith(lower)).collect(Collectors.toList());
    }
}
