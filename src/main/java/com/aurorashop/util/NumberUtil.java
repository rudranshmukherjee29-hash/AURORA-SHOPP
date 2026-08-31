package com.aurorashop.util;

import java.util.OptionalLong;

/**
 * Safe numeric parsing. Every quantity that ever reaches TransactionService
 * passes through here first — nothing downstream trusts a raw client- or
 * command-supplied number without going through {@link #parsePositiveLong}.
 */
public final class NumberUtil {

    private NumberUtil() {
    }

    /**
     * Parses a strictly positive long from user input. Rejects negative,
     * zero, non-numeric, and overflowing input rather than silently
     * clamping — a rejected quantity is safer than a guessed one.
     */
    public static OptionalLong parsePositiveLong(String input) {
        if (input == null || input.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            long value = Long.parseLong(input.trim());
            if (value <= 0) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(value);
        } catch (NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    public static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
