package com.sonar.trading.execution;

import java.math.BigDecimal;

/** One account's line in the post-confirm execution report (4.6 step 7 / 5). */
public record ExecutionLegResult(
        String accountId,
        boolean success,
        BigDecimal filledQty,
        BigDecimal avgPrice,
        BigDecimal slPrice,
        BigDecimal tpPrice,
        BigDecimal riskUsd,
        boolean stopVerified,
        String errorReason
) {
    public static ExecutionLegResult filled(String accountId, BigDecimal qty, BigDecimal avgPrice,
                                            BigDecimal sl, BigDecimal tp, BigDecimal riskUsd, boolean stopVerified) {
        return new ExecutionLegResult(accountId, true, qty, avgPrice, sl, tp, riskUsd, stopVerified, null);
    }

    public static ExecutionLegResult failed(String accountId, String reason) {
        return new ExecutionLegResult(accountId, false, null, null, null, null, null, false, reason);
    }
}
