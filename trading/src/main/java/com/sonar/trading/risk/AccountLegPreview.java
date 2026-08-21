package com.sonar.trading.risk;

import java.math.BigDecimal;

/** One account's line in the proposal preview report (4.8: POST /api/v1/strategy/proposals). */
public record AccountLegPreview(
        String accountId,
        boolean approved,
        BigDecimal qty,
        BigDecimal riskUsd,
        String rejectReason
) {
    public static AccountLegPreview approved(String accountId, BigDecimal qty, BigDecimal riskUsd) {
        return new AccountLegPreview(accountId, true, qty, riskUsd, null);
    }

    public static AccountLegPreview rejected(String accountId, String reason) {
        return new AccountLegPreview(accountId, false, null, null, reason);
    }
}
