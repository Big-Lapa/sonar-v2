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
        String status
) {}
