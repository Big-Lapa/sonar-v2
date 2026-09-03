package com.sonar.trading.risk.rules;

import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.risk.RiskContext;
import com.sonar.trading.risk.RiskDecision;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Rejects an OPEN on the symbol only when it would flip the position's direction — same-side OPEN
 * against an already-open position is a deliberate add-on (manual panel: "доливка", averages qty
 * and entry price in {@code ProposalExecutionService}), not pyramiding. Opposite-side OPEN still
 * rejects: flipping direction must go through an explicit close first, never silently net down.
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
        if (flat || pos.getSide().equals(ctx.side())) {
            return Optional.empty();
        }
        return Optional.of(RiskDecision.reject(
                "Position already open for " + pos.getSymbol()
                        + " (" + pos.getSide() + " qty=" + pos.getQuantity() + "), opposite side"));
    }

    @Override
    public int priority() { return 9; }
}
