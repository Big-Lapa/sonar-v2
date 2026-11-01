package com.sonar.trading.risk;

import com.sonar.trading.domain.entity.PositionEntity;

import java.math.BigDecimal;

/**
 * Everything a {@link com.sonar.trading.risk.rules.RiskRule} may inspect for one account leg of
 * a proposal. {@code candidateRisk} is null for CLOSE/REDUCE actions, which never size a
 * new entry.
 */
public record RiskContext(
        String accountId,
        String symbol,
        String action,   // OPEN | CLOSE | REDUCE
        String side,      // LONG (v1 long-only)
        AccountRiskProfile profile,
        PositionEntity currentPosition,
        long openPositionCount,
        BigDecimal currentEquity,
        BigDecimal equityAtOpen,
        BigDecimal peakEquity,
        BigDecimal todayRealizedPnl,
        BigDecimal openPortfolioRisk,
        BigDecimal candidateRisk,
        boolean autoPauseActive,
        Long lastLossAt
) {}
