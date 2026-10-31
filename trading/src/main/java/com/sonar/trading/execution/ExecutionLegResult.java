package com.sonar.trading.execution;

import com.sonar.trading.i18n.MessageCode;
import com.sonar.trading.i18n.Msg;

import java.math.BigDecimal;
import java.util.Map;

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
        String errorReason,
        /** Stable {@link MessageCode} name for client-side translation; null for plain-text reasons. */
        String errorCode,
        Map<String, String> errorParams
) {
    public static ExecutionLegResult filled(String accountId, BigDecimal qty, BigDecimal avgPrice,
                                            BigDecimal sl, BigDecimal tp, BigDecimal riskUsd, boolean stopVerified) {
        return new ExecutionLegResult(accountId, true, qty, avgPrice, sl, tp, riskUsd, stopVerified, null, null, Map.of());
    }

    public static ExecutionLegResult failed(String accountId, Msg msg) {
        return failed(accountId, msg.code().name(), msg.params(), msg.text());
    }

    public static ExecutionLegResult failed(String accountId, String code, Map<String, String> params, String reason) {
        return new ExecutionLegResult(accountId, false, null, null, null, null, null, false, reason,
                code, params != null ? params : Map.of());
    }

    /** A LIMIT entry placed on the exchange but not filled within the short post-place poll window
     *  — normal for a resting order away from market, not a failure. {@code success=true} (the
     *  order IS live on the exchange) but {@code filledQty=null} (nothing to record as a position
     *  yet — the reconciliation poller picks it up once/if it fills). */
    public static ExecutionLegResult limitWorking(String accountId) {
        Msg msg = Msg.of(MessageCode.LIMIT_ORDER_WORKING);
        return new ExecutionLegResult(accountId, true, null, null, null, null, null, false,
                msg.text(), msg.code().name(), msg.params());
    }
}
