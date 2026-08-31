package com.aurorashop.transaction;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents a single player from having two transactions in flight at once,
 * and throttles how quickly they can start a new one afterwards.
 * <p>
 * {@link #tryAcquire} is backed by {@code ConcurrentHashMap.newKeySet()},
 * whose {@code add()} is atomic — exactly one caller can ever succeed for a
 * given UUID at a time, which is the whole security property this class
 * exists to provide (see design brief section 9, "Simultaneous
 * transactions" / "Race conditions").
 */
public final class TransactionGuard {

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastTransactionMillis = new ConcurrentHashMap<>();

    /** Returns true if the lock was acquired. Caller MUST call {@link #release} exactly once if so. */
    public boolean tryAcquire(UUID playerId) {
        return inFlight.add(playerId);
    }

    public void release(UUID playerId) {
        inFlight.remove(playerId);
    }

    public boolean isRateLimited(UUID playerId, long rateLimitMillis) {
        Long last = lastTransactionMillis.get(playerId);
        return last != null && (System.currentTimeMillis() - last) < rateLimitMillis;
    }

    public void markTransactionTime(UUID playerId) {
        lastTransactionMillis.put(playerId, System.currentTimeMillis());
    }

    /** Called on disconnect/kick so stale state never lingers for a player who left mid-transaction. */
    public void clear(UUID playerId) {
        inFlight.remove(playerId);
        lastTransactionMillis.remove(playerId);
    }
}
