package com.sonar.trading.signal.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TradingSignalPayload(
        String signalId,
        String strategyId,
        String symbol,
        String side,
        String action,
        double confidence,
        String reason,
        String sourceWindow,
        long generatedAt,
        long validUntil,
        String sourceMarketEventId,
        String idempotencyKey,
        String entryPrice,
        String slPrice,
        String tpPrice,
        String qty,
        Long maxHoldMs
) {
    /** Pre-ADR-0031 shape — every existing (non-discretionary) call site/test keeps compiling;
     *  the new fields default to null (no explicit price/qty/hold-override for this signal). */
    public TradingSignalPayload(String signalId, String strategyId, String symbol, String side, String action,
                                double confidence, String reason, String sourceWindow,
                                long generatedAt, long validUntil,
                                String sourceMarketEventId, String idempotencyKey) {
        this(signalId, strategyId, symbol, side, action, confidence, reason, sourceWindow,
                generatedAt, validUntil, sourceMarketEventId, idempotencyKey,
                null, null, null, null, null);
    }
}
