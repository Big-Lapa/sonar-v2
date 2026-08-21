package com.sonar.trading.reconciliation.model;

import com.sonar.trading.exchange.model.AdapterOrderState;

import java.math.BigDecimal;

/**
 * An order's state as reported by the exchange, keyed by our {@code clientOrderId} (T17).
 * Carries only runtime fields — the local order's intent/audit (command_id, signal_id, …) is
 * never sourced from here.
 */
public record ExchangeOrderStatus(
        String clientOrderId,
        String exchangeOrderId,
        AdapterOrderState state,
        BigDecimal filledQty,
        BigDecimal avgPrice,
        BigDecimal fee
) {
    public boolean isTerminal() {
        return state == AdapterOrderState.FILLED
                || state == AdapterOrderState.CANCELED
                || state == AdapterOrderState.REJECTED
                || state == AdapterOrderState.PARTIALLY_FILLED;
    }
}
