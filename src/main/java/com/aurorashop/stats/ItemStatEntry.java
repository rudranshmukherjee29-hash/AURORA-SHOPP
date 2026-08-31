package com.aurorashop.stats;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mutable per-item accumulator. The two long counters use {@link AtomicLong}
 * directly; the two BigDecimal totals are guarded by synchronizing on this
 * instance, since BigDecimal has no lock-free atomic-update primitive and
 * per-item contention in a shop plugin is far too low for a monitor to
 * matter for performance.
 */
public final class ItemStatEntry {

    private final AtomicLong boughtCount = new AtomicLong();
    private final AtomicLong soldCount = new AtomicLong();
    private final AtomicLong transactionCount = new AtomicLong();
    private BigDecimal spent = BigDecimal.ZERO;
    private BigDecimal earned = BigDecimal.ZERO;

    public synchronized void recordBuy(long quantity, BigDecimal amount) {
        boughtCount.addAndGet(quantity);
        transactionCount.incrementAndGet();
        spent = spent.add(amount);
    }

    public synchronized void recordSell(long quantity, BigDecimal amount) {
        soldCount.addAndGet(quantity);
        transactionCount.incrementAndGet();
        earned = earned.add(amount);
    }

    public long boughtCount() {
        return boughtCount.get();
    }

    public long soldCount() {
        return soldCount.get();
    }

    public long transactionCount() {
        return transactionCount.get();
    }

    public synchronized BigDecimal spent() {
        return spent;
    }

    public synchronized BigDecimal earned() {
        return earned;
    }

    public synchronized void seed(long bought, long sold, BigDecimal spentAmount, BigDecimal earnedAmount, long transactions) {
        boughtCount.set(bought);
        soldCount.set(sold);
        transactionCount.set(transactions);
        spent = spentAmount;
        earned = earnedAmount;
    }
}
