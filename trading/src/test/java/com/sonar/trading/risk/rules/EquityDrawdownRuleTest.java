package com.sonar.trading.risk.rules;

import com.sonar.trading.risk.RiskContext;
import com.sonar.trading.risk.RiskDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** 4.2: drawdown from historical peak equity, NOT a daily measure. */
class EquityDrawdownRuleTest {

    private final EquityDrawdownRule rule = new EquityDrawdownRule();

    @Test
    void pausesTradingWhenDrawdownFromPeakReachesLimit() {
        // peak=10000, maxEquityDrawdownPct=0.15 -> equity must not drop below 8500.
        RiskContext ctx = TestContexts.withEquity(
                TestContexts.open(TestContexts.defaultProfile()),
                new BigDecimal("8500"), new BigDecimal("10000"));

        var decision = rule.evaluate(ctx);

        assertThat(decision).isPresent();
        assertThat(decision.get().outcome()).isEqualTo(RiskDecision.Outcome.PAUSE_TRADING);
        assertThat(decision.get().pauseCause()).isEqualTo(RiskDecision.PauseCause.MAX_DRAWDOWN);
    }

    @Test
    void approvesWhenWellBelowDrawdownLimit() {
        RiskContext ctx = TestContexts.withEquity(
                TestContexts.open(TestContexts.defaultProfile()),
                new BigDecimal("9800"), new BigDecimal("10000"));

        assertThat(rule.evaluate(ctx)).isEmpty();
    }
}
