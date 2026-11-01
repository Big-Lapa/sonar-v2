package com.sonar.trading.risk.rules;

import com.sonar.trading.risk.RiskContext;
import com.sonar.trading.risk.RiskDecision;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Rejects new OPEN signals when the account's open position count meets its limit. */
@Component
public class MaxOpenPositionsRule implements RiskRule {

    @Override
    public Optional<RiskDecision> evaluate(RiskContext ctx) {
        if (!"OPEN".equals(ctx.action())) return Optional.empty();
        if (ctx.openPositionCount() >= ctx.profile().maxOpenPositions()) {
            return Optional.of(RiskDecision.reject(
                    "Max open positions reached: " + ctx.openPositionCount()
                            + " >= " + ctx.profile().maxOpenPositions()));
        }
        return Optional.empty();
    }

    @Override
    public int priority() { return 2; }
}
