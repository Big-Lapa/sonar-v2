package com.sonar.trading.risk.rules;

import com.sonar.trading.risk.RiskContext;
import com.sonar.trading.risk.RiskDecision;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Triggers auto-pause when equity has fallen {@code maxEquityDrawdownPct} from its historical
 * peak — not a daily measure, and not auto-cleared on the UTC-day boundary like
 * {@link DailyLossLimitRule}; only a human clears it. Risk-reducing actions always allowed.
 */
@Component
public class EquityDrawdownRule implements RiskRule {

    @Override
    public Optional<RiskDecision> evaluate(RiskContext ctx) {
        if (!"OPEN".equals(ctx.action())) return Optional.empty();
        BigDecimal peak = ctx.peakEquity();
        BigDecimal equity = ctx.currentEquity();
        if (peak == null || equity == null || peak.signum() <= 0) return Optional.empty();

        BigDecimal drawdown = peak.subtract(equity).divide(peak, 10, java.math.RoundingMode.HALF_UP);
        if (drawdown.compareTo(ctx.profile().maxEquityDrawdownPct()) >= 0) {
            return Optional.of(RiskDecision.pauseTrading(
                    RiskDecision.PauseCause.MAX_DRAWDOWN,
                    "Equity drawdown from peak reached: drawdown=" + drawdown
                            + " limit=" + ctx.profile().maxEquityDrawdownPct()));
        }
        return Optional.empty();
    }

    @Override
    public int priority() { return 4; }
}
