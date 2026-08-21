package com.sonar.trading.reconciliation.model;

import java.math.BigDecimal;

/**
 * Account balance for one asset as reported by the exchange (BingX Swap V3) — used for
 * exchange-sourced PnL/equity (FU-3, ADR-0017) and {@code balance.updated} emission.
 *
 * <p>{@code equity} is the venue-reported equity when present; otherwise the reconciler computes
 * {@code balance + unrealizedPnl}.
 */
public record ExchangeBalance(
        String asset,
        BigDecimal balance,
        BigDecimal equity,
        BigDecimal unrealizedPnl,
        BigDecimal available,
        BigDecimal used
) {}
