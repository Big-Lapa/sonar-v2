package com.sonar.trading.risk.rules;

import com.sonar.trading.risk.RiskContext;
import com.sonar.trading.risk.RiskDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Blocks new OPEN entries during a cooldown window after a losing trade on the same symbol.
 * Global duration ({@code trading.risk.cooldown-after-loss-ms}) — v2's {@code account_risk}
 * table (4.1) has no per-account cooldown field, unlike v1's config-service RiskProfile; kept as
 * a simple app property since it isn't part of the money-sizing decisions in 4.2.
 */
@Component
public class CooldownRule implements RiskRule {

    private final long cooldownMs;

    public CooldownRule(@Value("${trading.risk.cooldown-after-loss-ms:1800000}") long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    @Override
    public Optional<RiskDecision> evaluate(RiskContext ctx) {
        if (!"OPEN".equals(ctx.action())) return Optional.empty();
        if (ctx.lastLossAt() == null) return Optional.empty();

        long elapsed = System.currentTimeMillis() - ctx.lastLossAt();
        if (elapsed < cooldownMs) {
            long remaining = cooldownMs - elapsed;
            return Optional.of(RiskDecision.reject(
                    "Cooldown active after loss: " + remaining + "ms remaining"));
        }
        return Optional.empty();
    }

    @Override
    public int priority() { return 5; }
}
