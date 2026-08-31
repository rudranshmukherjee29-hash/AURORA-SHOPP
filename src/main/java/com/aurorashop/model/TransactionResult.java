package com.aurorashop.model;

import java.math.BigDecimal;

/**
 * The outcome of a single buy/sell attempt. TransactionService always
 * returns one of these — nothing about a transaction outcome is ever
 * communicated by exception or by mutating caller state, which keeps the
 * success/failure path impossible to misinterpret at the call site.
 */
public record TransactionResult(
        Status status,
        TransactionType type,
        String itemId,
        long quantity,
        BigDecimal amount // total price (buy) or payout (sell); may be zero on failure
) {

    public static TransactionResult success(TransactionType type, String itemId, long quantity, BigDecimal amount) {
        return new TransactionResult(Status.SUCCESS, type, itemId, quantity, amount);
    }

    public static TransactionResult failure(Status status, TransactionType type, String itemId, long quantity) {
        if (status == Status.SUCCESS) {
            throw new IllegalArgumentException("Use success() for SUCCESS results");
        }
        return new TransactionResult(status, type, itemId, quantity, BigDecimal.ZERO);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public enum Status {
        SUCCESS,
        INVALID_QUANTITY,
        INSUFFICIENT_FUNDS,
        INSUFFICIENT_ITEMS,
        INVENTORY_FULL,
        OUT_OF_STOCK,
        ECONOMY_FAILURE,
        TRANSACTION_CONFLICT,
        CONFIGURATION_ERROR,
        DAILY_LIMIT_REACHED,
        NOT_VANILLA_ITEM,
        BUY_DISABLED,
        SELL_DISABLED,
        RATE_LIMITED
    }
}
