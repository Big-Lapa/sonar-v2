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
 *
 * <p>The platform is LONG-only today ({@code ProposalPreviewService} always builds
 * {@link RiskContext} with {@code side="LONG"}), so the opposite-side branch is currently
 * unreachable — kept for when shorts land rather than deleted and rewritten later. This rule was
 * never the thing capping HOW MUCH an operator could add on the same side; that cap is
 * {@code ProposalExecutionService.executeLeg}'s add-on notional/leverage gate against the
 * account's own caps, computed on the position's combined size, not this rule.
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
        if (flat || ctx.side().equals(pos.getSide())) {
            return Optional.empty();
        }
        return Optional.of(RiskDecision.reject(
                "Position already open for " + pos.getSymbol()
                        + " (" + pos.getSide() + " qty=" + pos.getQuantity() + "), opposite side"));
    }

    @Override
    public int priority() { return 9; }
}
