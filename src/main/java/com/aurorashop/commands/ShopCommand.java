package com.aurorashop.commands;

import com.aurorashop.config.MessageManager;
import com.aurorashop.gui.ShopGuiManager;
import com.aurorashop.model.ShopCategory;
import com.aurorashop.shop.ShopRegistry;
import com.aurorashop.util.TextUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class ShopCommand implements CommandExecutor, TabCompleter {

    private final ShopRegistry registry;
    private final ShopGuiManager guiManager;
    private final MessageManager messages;

    public ShopCommand(ShopRegistry registry, ShopGuiManager guiManager, MessageManager messages) {
        this.registry = registry;
        this.guiManager = guiManager;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return true;
        }

        if (args.length == 0) {
            guiManager.openMainMenu(player);
            return true;
        }

        if (args[0].equalsIgnoreCase("search")) {
            if (args.length < 2) {
                messages.send(player, "shop.no-search-term");
                return true;
            }
            String query = String.join(" ", List.of(args).subList(1, args.length));
            if (registry.search(query).isEmpty()) {
                messages.send(player, "shop.no-results", TextUtil.map("query", query));
                return true;
            }
            guiManager.openSearch(player, query, 0);
            return true;
        }

        String categoryId = args[0].toLowerCase(java.util.Locale.ROOT);
        if (registry.category(categoryId).isEmpty()) {
            messages.send(player, "shop.unknown-category", TextUtil.map("category", args[0]));
            return true;
        }
        guiManager.openCategory(player, categoryId, 0);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            options.add("search");
            options.addAll(registry.categoriesOrdered().stream().map(ShopCategory::id).collect(Collectors.toList()));
            String partial = args[0].toLowerCase(java.util.Locale.ROOT);
            return options.stream().filter(o -> o.toLowerCase(java.util.Locale.ROOT).startsWith(partial)).collect(Collectors.toList());
        }
        return List.of();
    }
}
