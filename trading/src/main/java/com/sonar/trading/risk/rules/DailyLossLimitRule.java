package com.sonar.trading.risk.rules;

import com.sonar.trading.risk.RiskContext;
import com.sonar.trading.risk.RiskDecision;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Triggers auto-pause when today's realized loss (closed trades + fees + funding, NOT open
 * unrealized PnL) exceeds {@code maxDailyRealizedLossPct} of the equity at 00:00 UTC.
 * Risk-reducing actions (CLOSE/REDUCE) are always allowed.
 */
@Component
public class DailyLossLimitRule implements RiskRule {

    @Override
    public Optional<RiskDecision> evaluate(RiskContext ctx) {
        if (!"OPEN".equals(ctx.action())) return Optional.empty();
        if (ctx.todayRealizedPnl() == null || ctx.equityAtOpen() == null) return Optional.empty();

        var limit = ctx.equityAtOpen().multiply(ctx.profile().maxDailyRealizedLossPct());
        if (ctx.todayRealizedPnl().negate().compareTo(limit) >= 0) {
            return Optional.of(RiskDecision.pauseTrading(
                    RiskDecision.PauseCause.DAILY_LOSS,
                    "Daily realized loss limit reached: loss=" + ctx.todayRealizedPnl().negate()
                            + " limit=" + limit));
        }
        return Optional.empty();
    }

    @Override
    public int priority() { return 3; }
}
