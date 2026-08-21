package com.sonar.trading.exchange.bingx;

import java.math.BigDecimal;

/**
 * Typed TP or SL attachment for a BingX order (T23). Serializes to the JSON string
 * BingX expects in the {@code takeProfit}/{@code stopLoss} fields of a place-order request.
 *
 * <p>stopPrice and price must be JSON numbers — BingX rejects quoted strings (code 109400).
 * Use {@link #of(BingxOrderType, BigDecimal, BingxWorkingType)} for the common case where
 * the trigger price and limit price are the same level.
 */
public record BingxTpSlOrder(
        BingxOrderType type,
        BigDecimal stopPrice,
        BigDecimal price,
        BingxWorkingType workingType
) {

    /** Common case: stopPrice == price (trigger and limit at the same level). */
    public static BingxTpSlOrder of(BingxOrderType type, BigDecimal price, BingxWorkingType workingType) {
        return new BingxTpSlOrder(type, price, price, workingType);
    }

    /** Serialize to the JSON string BingX expects. Both price fields are JSON numbers. */
    public String toJson() {
        String sp = stopPrice.stripTrailingZeros().toPlainString();
        String p  = price.stripTrailingZeros().toPlainString();
        return "{\"type\":\"" + type.name() + "\",\"stopPrice\":" + sp
                + ",\"price\":" + p + ",\"workingType\":\"" + workingType.name() + "\"}";
    }
}
