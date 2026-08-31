package com.aurorashop.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MiniMessage parsing helpers. AuroraShop never uses legacy '&'/ChatColor
 * formatting internally — everything is parsed as MiniMessage into
 * Adventure Components and applied directly via the Paper API
 * (ItemMeta#displayName(Component), Player#sendMessage(Component), etc).
 */
public final class TextUtil {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final DecimalFormat MONEY_FORMAT = new DecimalFormat("#,##0.00");

    private TextUtil() {
    }

    public static Component parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        return MM.deserialize(raw).decoration(TextDecoration.ITALIC, false);
    }

    public static Component parse(String raw, Map<String, String> placeholders) {
        if (raw == null || raw.isEmpty()) {
            return Component.empty();
        }
        TagResolver.Builder builder = TagResolver.builder();
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            builder.resolver(Placeholder.parsed(e.getKey(), e.getValue()));
        }
        return MM.deserialize(raw, builder.build()).decoration(TextDecoration.ITALIC, false);
    }

    /** Convenience builder for placeholder maps: TextUtil.map("item", "Iron Ingot", "price", "$14.00") */
    public static Map<String, String> map(String... kv) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }

    public static String money(BigDecimal amount) {
        return "$" + MONEY_FORMAT.format(amount);
    }

    public static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
    }
}
