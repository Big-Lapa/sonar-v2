package com.sonar.trading.risk.rules;

import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.risk.AccountRiskProfile;
import com.sonar.trading.risk.RiskContext;

import java.math.BigDecimal;

/** Minimal builder so each rule test only sets the fields it actually exercises. */
final class TestContexts {

    private TestContexts() {}

    static AccountRiskProfile defaultProfile() {
        return new AccountRiskProfile("acct-1", new BigDecimal("0.005"), new BigDecimal("0.02"),
                new BigDecimal("2000"), new BigDecimal("0.03"), new BigDecimal("0.15"),
                5, 5, true);
    }

    static RiskContext open(AccountRiskProfile profile) {
        return new RiskContext("acct-1", "SOLUSDT", "OPEN", "LONG", profile, null, 0,
                new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("10000"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("50"), false, null);
    }

    static RiskContext withProfile(RiskContext base, AccountRiskProfile profile) {
        return new RiskContext(base.accountId(), base.symbol(), base.action(), base.side(), profile,
                base.currentPosition(), base.openPositionCount(), base.currentEquity(), base.equityAtOpen(),
                base.peakEquity(), base.todayRealizedPnl(), base.openPortfolioRisk(), base.candidateRisk(),
                base.autoPauseActive(), base.lastLossAt());
    }

    static RiskContext withPosition(RiskContext base, PositionEntity position) {
        return new RiskContext(base.accountId(), base.symbol(), base.action(), base.side(), base.profile(),
                position, base.openPositionCount(), base.currentEquity(), base.equityAtOpen(),
                base.peakEquity(), base.todayRealizedPnl(), base.openPortfolioRisk(), base.candidateRisk(),
                base.autoPauseActive(), base.lastLossAt());
    }

    static RiskContext withOpenCount(RiskContext base, long count) {
        return new RiskContext(base.accountId(), base.symbol(), base.action(), base.side(), base.profile(),
                base.currentPosition(), count, base.currentEquity(), base.equityAtOpen(),
                base.peakEquity(), base.todayRealizedPnl(), base.openPortfolioRisk(), base.candidateRisk(),
                base.autoPauseActive(), base.lastLossAt());
    }

    static RiskContext withEquity(RiskContext base, BigDecimal currentEquity, BigDecimal peakEquity) {
        return new RiskContext(base.accountId(), base.symbol(), base.action(), base.side(), base.profile(),
                base.currentPosition(), base.openPositionCount(), currentEquity, base.equityAtOpen(),
                peakEquity, base.todayRealizedPnl(), base.openPortfolioRisk(), base.candidateRisk(),
                base.autoPauseActive(), base.lastLossAt());
    }

    static RiskContext withRealizedPnl(RiskContext base, BigDecimal realizedPnl) {
        return new RiskContext(base.accountId(), base.symbol(), base.action(), base.side(), base.profile(),
                base.currentPosition(), base.openPositionCount(), base.currentEquity(), base.equityAtOpen(),
                base.peakEquity(), realizedPnl, base.openPortfolioRisk(), base.candidateRisk(),
                base.autoPauseActive(), base.lastLossAt());
    }

    static RiskContext withPortfolioRisk(RiskContext base, BigDecimal openRisk, BigDecimal candidateRisk) {
        return new RiskContext(base.accountId(), base.symbol(), base.action(), base.side(), base.profile(),
                base.currentPosition(), base.openPositionCount(), base.currentEquity(), base.equityAtOpen(),
                base.peakEquity(), base.todayRealizedPnl(), openRisk, candidateRisk,
                base.autoPauseActive(), base.lastLossAt());
    }

    static RiskContext withAutoPause(RiskContext base, boolean autoPaused) {
        return new RiskContext(base.accountId(), base.symbol(), base.action(), base.side(), base.profile(),
                base.currentPosition(), base.openPositionCount(), base.currentEquity(), base.equityAtOpen(),
                base.peakEquity(), base.todayRealizedPnl(), base.openPortfolioRisk(), base.candidateRisk(),
                autoPaused, base.lastLossAt());
    }

    static RiskContext withLastLossAt(RiskContext base, Long lastLossAt) {
        return new RiskContext(base.accountId(), base.symbol(), base.action(), base.side(), base.profile(),
                base.currentPosition(), base.openPositionCount(), base.currentEquity(), base.equityAtOpen(),
                base.peakEquity(), base.todayRealizedPnl(), base.openPortfolioRisk(), base.candidateRisk(),
                base.autoPauseActive(), lastLossAt);
    }

    static RiskContext withAction(RiskContext base, String action) {
        return new RiskContext(base.accountId(), base.symbol(), action, base.side(), base.profile(),
                base.currentPosition(), base.openPositionCount(), base.currentEquity(), base.equityAtOpen(),
                base.peakEquity(), base.todayRealizedPnl(), base.openPortfolioRisk(), base.candidateRisk(),
                base.autoPauseActive(), base.lastLossAt());
    }
}
