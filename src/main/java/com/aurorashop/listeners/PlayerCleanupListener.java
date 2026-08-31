package com.aurorashop.listeners;

import com.aurorashop.gui.ShopGuiManager;
import com.aurorashop.transaction.TransactionGuard;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Per design brief section 13 ("Clean up player transaction locks on
 * disconnect"): if a player disconnects mid-transaction, or right after
 * one, their guard entry and GUI session must not linger forever. Since
 * {@link TransactionGuard} entries are only ever held for the duration of
 * a single synchronous method call, a disconnect can only ever find the
 * guard already released — this handler exists purely as a safety net in
 * case a future change ever makes that call asynchronous.
 */
public final class PlayerCleanupListener implements Listener {

    private final TransactionGuard guard;
    private final ShopGuiManager guiManager;

    public PlayerCleanupListener(TransactionGuard guard, ShopGuiManager guiManager) {
        this.guard = guard;
        this.guiManager = guiManager;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cleanup(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        cleanup(event.getPlayer().getUniqueId());
    }

    private void cleanup(java.util.UUID playerId) {
        guard.clear(playerId);
        guiManager.clearSession(playerId);
    }
}
