package com.sonar.trading.exchange.dto;

import java.math.BigDecimal;

/**
 * One resting order as reported by the exchange — the manual panel's cancel-order screen (a
 * resting LIMIT entry, a stray conditional order left by hand-editing on the exchange itself,
 * etc). Runtime venue state only, never our own intent/audit.
 */
public record AdapterOpenOrder(
        String orderId,
        String clientOrderId,
        String symbol,
        String side,
        String type,
        BigDecimal qty,
        BigDecimal price,
        /**
         * Trigger level of a conditional order (STOP_MARKET / TAKE_PROFIT_MARKET and friends).
         * Those carry {@code price = 0} — the level that matters lives in the venue's own trigger
         * field ({@code stopPrice} on BingX, {@code triggerPrice} on Bybit), so without this the
         * panel showed a column of zeros exactly where the operator looks for their stops. Null
         * for ordinary LIMIT orders, whose {@code price} is the real one.
         */
        BigDecimal triggerPrice,
        String status
) {}
