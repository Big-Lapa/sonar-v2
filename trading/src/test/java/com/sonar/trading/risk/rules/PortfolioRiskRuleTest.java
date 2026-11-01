package com.sonar.trading.risk.rules;

import com.sonar.trading.risk.RiskContext;
import com.sonar.trading.risk.RiskDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** sum(planned risk of open positions) + candidate risk vs maxPortfolioRiskPct × equity. */
class PortfolioRiskRuleTest {

    private final PortfolioRiskRule rule = new PortfolioRiskRule();

    @Test
    void rejectsWhenOpenPlusCandidateExceedsLimit() {
        // equity=10000, maxPortfolioRiskPct=0.02 -> limit=200. open=180 + candidate=50 = 230 > 200.
        RiskContext ctx = TestContexts.withPortfolioRisk(
                TestContexts.open(TestContexts.defaultProfile()),
                new BigDecimal("180"), new BigDecimal("50"));

        var decision = rule.evaluate(ctx);

        assertThat(decision).isPresent();
        assertThat(decision.get().outcome()).isEqualTo(RiskDecision.Outcome.REJECT);
        assertThat(decision.get().reason()).contains("Portfolio risk limit exceeded");
    }

    @Test
    void approvesWhenWithinLimit() {
        RiskContext ctx = TestContexts.withPortfolioRisk(
                TestContexts.open(TestContexts.defaultProfile()),
                new BigDecimal("100"), new BigDecimal("50"));

        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void closeActionsAreExempt() {
        RiskContext ctx = TestContexts.withAction(
                TestContexts.withPortfolioRisk(TestContexts.open(TestContexts.defaultProfile()),
                        new BigDecimal("9999"), new BigDecimal("9999")),
                "CLOSE");

        assertThat(rule.evaluate(ctx)).isEmpty();
    }
}
