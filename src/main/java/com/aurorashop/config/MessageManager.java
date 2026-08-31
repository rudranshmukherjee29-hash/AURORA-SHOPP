package com.aurorashop.config;

import com.aurorashop.util.TextUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

public final class MessageManager {

    private final JavaPlugin plugin;
    private FileConfiguration messages;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messages = YamlConfiguration.loadConfiguration(file);
    }

    private String raw(String path) {
        return messages.getString(path, "<red>Missing message: " + path);
    }

    private String prefix() {
        return messages.getString("prefix", "");
    }

    public Component get(String path) {
        return TextUtil.parse(raw(path));
    }

    public Component get(String path, Map<String, String> placeholders) {
        return TextUtil.parse(raw(path), placeholders);
    }

    /** Sends a message with the configured prefix prepended. Works for players and console alike. */
    public void send(CommandSender recipient, String path) {
        String body = raw(path);
        if (body.isEmpty()) {
            return; // intentionally silent messages (e.g. "opened-main")
        }
        recipient.sendMessage(TextUtil.parse(prefix() + body));
    }

    public void send(CommandSender recipient, String path, Map<String, String> placeholders) {
        String body = raw(path);
        if (body.isEmpty()) {
            return;
        }
        recipient.sendMessage(TextUtil.parse(prefix() + body, placeholders));
    }
}
