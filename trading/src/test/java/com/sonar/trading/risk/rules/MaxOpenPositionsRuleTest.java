package com.sonar.trading.risk.rules;

import com.sonar.trading.risk.RiskContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaxOpenPositionsRuleTest {

    private final MaxOpenPositionsRule rule = new MaxOpenPositionsRule();

    @Test
    void rejectsWhenAtLimit() {
        // defaultProfile() has maxOpenPositions=5
        RiskContext ctx = TestContexts.withOpenCount(TestContexts.open(TestContexts.defaultProfile()), 5);
        assertThat(rule.evaluate(ctx)).isPresent();
    }

    @Test
    void approvesBelowLimit() {
        RiskContext ctx = TestContexts.withOpenCount(TestContexts.open(TestContexts.defaultProfile()), 4);
        assertThat(rule.evaluate(ctx)).isEmpty();
    }
}
