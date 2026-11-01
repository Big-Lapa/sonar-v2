package com.sonar.trading.risk.rules;

import com.sonar.trading.risk.RiskContext;
import com.sonar.trading.risk.RiskDecision;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Blocks new entries while auto-pause is active. Risk-reducing actions (CLOSE/REDUCE) are
 * always allowed: a paused account must still be able to flatten exposure. v2 has no global kill
 * switch (no config-service) — auto-pause is the only halt mechanism.
 */
@Component
public class TradingModeRule implements RiskRule {

    @Override
    public Optional<RiskDecision> evaluate(RiskContext ctx) {
        if (!"OPEN".equals(ctx.action())) return Optional.empty();
        if (ctx.autoPauseActive()) {
            return Optional.of(RiskDecision.reject("auto-pause is active"));
        }
        return Optional.empty();
    }

    @Override
    public int priority() { return 0; }
}
