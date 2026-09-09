package com.sonar.trading.risk.rules;

import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.risk.RiskContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Same-side OPEN against an already-open position is an add-on ("доливка"); opposite-side still rejects. */
class NoStackingRuleTest {

    private final NoStackingRule rule = new NoStackingRule();

    @Test
    void rejectsOpenWhenPositionAlreadyOpenOnOppositeSide() {
        PositionEntity open = Mockito.mock(PositionEntity.class);
        when(open.getSide()).thenReturn("SHORT");
        when(open.getStatus()).thenReturn("OPEN");
        when(open.getSymbol()).thenReturn("SOLUSDT");
        when(open.getQuantity()).thenReturn(new BigDecimal("5"));

        RiskContext ctx = TestContexts.withPosition(TestContexts.open(TestContexts.defaultProfile()), open);

        var decision = rule.evaluate(ctx);
        assertThat(decision).isPresent();
    }

    @Test
    void approvesOpenWhenPositionAlreadyOpenOnSameSide() {
        PositionEntity open = Mockito.mock(PositionEntity.class);
        when(open.getSide()).thenReturn("LONG");
        when(open.getStatus()).thenReturn("OPEN");
        when(open.getSymbol()).thenReturn("SOLUSDT");
        when(open.getQuantity()).thenReturn(new BigDecimal("5"));

        RiskContext ctx = TestContexts.withPosition(TestContexts.open(TestContexts.defaultProfile()), open);

        assertThat(rule.evaluate(ctx)).isEmpty();
    }

    @Test
    void approvesOpenWhenFlat() {
        RiskContext ctx = TestContexts.open(TestContexts.defaultProfile()); // currentPosition = null
        assertThat(rule.evaluate(ctx)).isEmpty();
    }
}
