package com.sonar.trading.risk.rules;

import com.sonar.trading.risk.RiskContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CooldownRuleTest {

    private final CooldownRule rule = new CooldownRule(1_800_000L); // 30 min

    @Test
    void rejectsWithinCooldownWindow() {
        long fiveMinAgo = System.currentTimeMillis() - 5 * 60_000L;
        RiskContext ctx = TestContexts.withLastLossAt(TestContexts.open(TestContexts.defaultProfile()), fiveMinAgo);
        assertThat(rule.evaluate(ctx)).isPresent();
    }

    @Test
    void approvesAfterCooldownExpires() {
        long fortyMinAgo = System.currentTimeMillis() - 40 * 60_000L;
        RiskContext ctx = TestContexts.withLastLossAt(TestContexts.open(TestContexts.defaultProfile()), fortyMinAgo);
        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void approvesWhenNoPriorLoss() {
        RiskContext ctx = TestContexts.open(TestContexts.defaultProfile()); // lastLossAt = null
        assertThat(rule.evaluate(ctx)).isEmpty();
    }
}
