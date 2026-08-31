package com.aurorashop.db;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionRecord(
        long timestampMillis,
        UUID playerId,
        String playerName,
        String itemId,
        String type,       // "BUY" or "SELL"
        long quantity,
        BigDecimal amount,
        boolean success,
        String resultStatus // TransactionResult.Status name, for diagnostics
) {
}
