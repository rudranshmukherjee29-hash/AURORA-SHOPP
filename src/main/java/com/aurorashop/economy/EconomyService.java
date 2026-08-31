package com.aurorashop.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.logging.Level;

/**
 * The ONLY class in AuroraShop that touches Vault's {@link Economy}
 * directly. Every withdraw/deposit call here returns an explicit
 * success/failure signal that the caller must check — nobody downstream is
 * allowed to assume money moved just because the call didn't throw.
 * <p>
 * AuroraShop never implements its own balance storage: Vault (backed by
 * whatever plugin registers an Economy provider, typically EssentialsX) is
 * the single source of truth for player balances.
 */
public final class EconomyService {

    private final JavaPlugin plugin;
    private Economy economy;

    public EconomyService(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Locates a registered Vault Economy provider. Returns false (and logs
     * clearly) if Vault isn't present or no provider has registered — the
     * caller is expected to disable the plugin in that case rather than
     * limp along with a broken economy link.
     */
    public boolean setup() {
        if (plugin.getServer().getPluginManager().getPlugin("Vault") == null) {
            plugin.getLogger().severe("Vault is not installed. AuroraShop cannot function without it.");
            return false;
        }
        RegisteredServiceProvider<Economy> provider =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            plugin.getLogger().severe("No Economy provider is registered with Vault. Install an economy "
                    + "plugin such as EssentialsX before starting AuroraShop.");
            return false;
        }
        this.economy = provider.getProvider();
        plugin.getLogger().info("Using Vault economy provider: " + economy.getName());
        return true;
    }

    public boolean isReady() {
        return economy != null;
    }

    public Optional<String> providerName() {
        return economy == null ? Optional.empty() : Optional.of(economy.getName());
    }

    public BigDecimal getBalance(OfflinePlayer player) {
        return BigDecimal.valueOf(economy.getBalance(player));
    }

    public boolean has(OfflinePlayer player, BigDecimal amount) {
        return economy.has(player, amount.doubleValue());
    }

    /** Withdraws {@code amount} from {@code player}. Returns true only on confirmed success. */
    public boolean withdraw(OfflinePlayer player, BigDecimal amount) {
        try {
            EconomyResponse response = economy.withdrawPlayer(player, amount.doubleValue());
            if (response == null) {
                plugin.getLogger().severe("Economy provider returned a null response on withdraw for "
                        + player.getUniqueId());
                return false;
            }
            if (!response.transactionSuccess()) {
                plugin.getLogger().warning("Withdraw failed for " + player.getUniqueId() + ": " + response.errorMessage);
            }
            return response.transactionSuccess();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Economy provider threw during withdraw", e);
            return false;
        }
    }

    /** Deposits {@code amount} to {@code player}. Returns true only on confirmed success. */
    public boolean deposit(OfflinePlayer player, BigDecimal amount) {
        try {
            EconomyResponse response = economy.depositPlayer(player, amount.doubleValue());
            if (response == null) {
                plugin.getLogger().severe("Economy provider returned a null response on deposit for "
                        + player.getUniqueId());
                return false;
            }
            if (!response.transactionSuccess()) {
                plugin.getLogger().warning("Deposit failed for " + player.getUniqueId() + ": " + response.errorMessage);
            }
            return response.transactionSuccess();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Economy provider threw during deposit", e);
            return false;
        }
    }

    public String format(BigDecimal amount) {
        try {
            String formatted = economy.format(amount.doubleValue());
            if (formatted == null || formatted.isBlank()) {
                plugin.getLogger().warning("Economy provider '" + economy.getName()
                        + "' returned a blank formatted amount — falling back to AuroraShop's own "
                        + "formatting. Check your economy plugin's currency-format configuration.");
                return com.aurorashop.util.TextUtil.money(amount);
            }
            return formatted;
        } catch (Exception e) {
            return com.aurorashop.util.TextUtil.money(amount);
        }
    }
}
