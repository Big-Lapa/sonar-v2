package com.sonar.trading.reconciliation.model;

import java.math.BigDecimal;

/**
 * A position as reported by the exchange (BingX) — the source of truth for reconciliation (T17).
 * Symbol is the core form ({@code BTCUSDT}); side is normalized to LONG/SHORT/FLAT; qty is the
 * absolute size. All numeric fields are runtime values only — they never carry our intent/audit.
 */
public record ExchangePosition(
        String symbol,
        String side,
        BigDecimal qty,
        BigDecimal avgPrice,
        BigDecimal markPrice,
        BigDecimal unrealizedPnl,
        Integer leverage,
        BigDecimal liquidationPrice
) {
    public boolean isFlat() {
        return qty == null || qty.signum() == 0 || "FLAT".equals(side);
    }
}
