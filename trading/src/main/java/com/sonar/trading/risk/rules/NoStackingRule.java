package com.sonar.trading.risk.rules;

import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.risk.RiskContext;
import com.sonar.trading.risk.RiskDecision;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Rejects an OPEN when a position on the symbol is already open on this account — one position
 * per (account, symbol), no pyramiding (ported from v1 unchanged; still applies in v2).
 */
@Component
public class NoStackingRule implements RiskRule {

    @Override
    public Optional<RiskDecision> evaluate(RiskContext ctx) {
        if (!"OPEN".equals(ctx.action())) {
            return Optional.empty();
        }
        PositionEntity pos = ctx.currentPosition();
        boolean flat = pos == null
                || "FLAT".equals(pos.getSide())
                || "CLOSED".equals(pos.getStatus());
        if (flat) {
            return Optional.empty();
        }
        return Optional.of(RiskDecision.reject(
                "Position already open for " + pos.getSymbol()
                        + " (" + pos.getSide() + " qty=" + pos.getQuantity() + ")"));
    }

    @Override
    public int priority() { return 9; }
}
