package com.sonar.trading.risk;

import com.sonar.trading.i18n.Msg;

import java.math.BigDecimal;
import java.util.Map;

/** One account's line in the proposal preview report (POST /api/v1/strategy/proposals). */
public record AccountLegPreview(
        String accountId,
        boolean approved,
        BigDecimal qty,
        BigDecimal riskUsd,
        String rejectReason,
        String rejectCode,
        Map<String, String> rejectParams
) {
    public static AccountLegPreview approved(String accountId, BigDecimal qty, BigDecimal riskUsd) {
        return new AccountLegPreview(accountId, true, qty, riskUsd, null, null, Map.of());
    }

    public static AccountLegPreview rejected(String accountId, Msg msg) {
        return new AccountLegPreview(accountId, false, null, null, msg.text(), msg.code().name(), msg.params());
    }

    /** Rejection that already carries its own code (e.g. a sizing or risk-engine reason). */
    public static AccountLegPreview rejected(String accountId, String code, Map<String, String> params, String reason) {
        return new AccountLegPreview(accountId, false, null, null, reason, code, params != null ? params : Map.of());
    }

    /** Plain-text rejection with no translatable code. */
    public static AccountLegPreview rejected(String accountId, String reason) {
        return rejected(accountId, null, Map.of(), reason);
    }
}
