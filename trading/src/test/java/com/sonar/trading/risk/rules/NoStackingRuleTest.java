package com.sonar.trading.risk.rules;

import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.risk.RiskContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** One position per (account, symbol) — no pyramiding. */
class NoStackingRuleTest {

    private final NoStackingRule rule = new NoStackingRule();

    @Test
    void rejectsOpenWhenPositionAlreadyOpen() {
        PositionEntity open = Mockito.mock(PositionEntity.class);
        when(open.getSide()).thenReturn("LONG");
        when(open.getStatus()).thenReturn("OPEN");
        when(open.getSymbol()).thenReturn("SOLUSDT");
        when(open.getQuantity()).thenReturn(new BigDecimal("5"));

        RiskContext ctx = TestContexts.withPosition(TestContexts.open(TestContexts.defaultProfile()), open);

        var decision = rule.evaluate(ctx);
        assertThat(decision).isPresent();
    }

    @Test
    void approvesOpenWhenFlat() {
        RiskContext ctx = TestContexts.open(TestContexts.defaultProfile()); // currentPosition = null
        assertThat(rule.evaluate(ctx)).isEmpty();
    }
}
