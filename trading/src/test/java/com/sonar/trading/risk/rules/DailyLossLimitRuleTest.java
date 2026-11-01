package com.sonar.trading.risk.rules;

import com.sonar.trading.risk.RiskContext;
import com.sonar.trading.risk.RiskDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** today's REALIZED loss vs maxDailyRealizedLossPct × equity at 00:00 UTC. */
class DailyLossLimitRuleTest {

    private final DailyLossLimitRule rule = new DailyLossLimitRule();

    @Test
    void pausesTradingWhenDailyLossLimitReached() {
        // equityAtOpen=10000, maxDailyRealizedLossPct=0.03 -> limit=300. loss=-300 reaches it.
        RiskContext ctx = TestContexts.withRealizedPnl(
                TestContexts.open(TestContexts.defaultProfile()), new BigDecimal("-300"));

        var decision = rule.evaluate(ctx);

        assertThat(decision).isPresent();
        assertThat(decision.get().outcome()).isEqualTo(RiskDecision.Outcome.PAUSE_TRADING);
        assertThat(decision.get().pauseCause()).isEqualTo(RiskDecision.PauseCause.DAILY_LOSS);
    }

    @Test
    void unrealizedMovementOnAnOpenPositionDoesNotCount() {
        // realizedPnl stays 0 even though the account might be deep underwater on paper.
        RiskContext ctx = TestContexts.withRealizedPnl(
                TestContexts.open(TestContexts.defaultProfile()), BigDecimal.ZERO);

        assertThat(rule.evaluate(ctx)).isEmpty();
    }
}
