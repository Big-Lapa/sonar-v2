package com.sonar.trading.risk;

import com.sonar.trading.domain.entity.AccountRiskEntity;

import java.math.BigDecimal;

/** Parsed view of {@link AccountRiskEntity} — decimal strings turned into {@link BigDecimal} once. */
public record AccountRiskProfile(
        String accountId,
        BigDecimal riskPctPerTrade,
        BigDecimal maxPortfolioRiskPct,
        BigDecimal maxPositionNotional,
        BigDecimal maxDailyRealizedLossPct,
        BigDecimal maxEquityDrawdownPct,
        int maxOpenPositions,
        int maxLeverage,
        boolean tradable
) {
    public static AccountRiskProfile from(AccountRiskEntity e) {
        return new AccountRiskProfile(
                e.getAccountId(),
                new BigDecimal(e.getRiskPctPerTrade()),
                new BigDecimal(e.getMaxPortfolioRiskPct()),
                new BigDecimal(e.getMaxPositionNotional()),
                new BigDecimal(e.getMaxDailyRealizedLossPct()),
                new BigDecimal(e.getMaxEquityDrawdownPct()),
                e.getMaxOpenPositions(),
                e.getMaxLeverage(),
                e.isTradable());
    }
}
