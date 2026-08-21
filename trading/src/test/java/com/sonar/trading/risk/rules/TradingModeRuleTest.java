package com.sonar.trading.risk.rules;

import com.sonar.trading.risk.RiskContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradingModeRuleTest {

    private final TradingModeRule rule = new TradingModeRule();

    @Test
    void rejectsOpenWhileAutoPaused() {
        RiskContext ctx = TestContexts.withAutoPause(TestContexts.open(TestContexts.defaultProfile()), true);
        assertThat(rule.evaluate(ctx)).isPresent();
    }

    @Test
    void closeIsAllowedEvenWhileAutoPaused() {
        RiskContext ctx = TestContexts.withAction(
                TestContexts.withAutoPause(TestContexts.open(TestContexts.defaultProfile()), true), "CLOSE");
        assertThat(rule.evaluate(ctx)).isEmpty();
    }
}
