package com.sonar.trading.risk.rules;

import com.sonar.trading.risk.RiskContext;
import com.sonar.trading.risk.RiskDecision;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Rejects an OPEN when the sum of planned risk on already-open positions plus this candidate's
 * risk would exceed {@code maxPortfolioRiskPct} of current equity. Portfolio risk is
 * counted from the PLANNED figure fixed at each position's open time, not current price — so a
 * favorable move never silently frees up portfolio headroom.
 */
@Component
public class PortfolioRiskRule implements RiskRule {

    @Override
    public Optional<RiskDecision> evaluate(RiskContext ctx) {
        if (!"OPEN".equals(ctx.action())) return Optional.empty();
        if (ctx.candidateRisk() == null || ctx.currentEquity() == null) return Optional.empty();

        BigDecimal open = ctx.openPortfolioRisk() != null ? ctx.openPortfolioRisk() : BigDecimal.ZERO;
        BigDecimal total = open.add(ctx.candidateRisk());
        BigDecimal limit = ctx.currentEquity().multiply(ctx.profile().maxPortfolioRiskPct());

        if (total.compareTo(limit) > 0) {
            return Optional.of(RiskDecision.reject(
                    "Portfolio risk limit exceeded: open=" + open + " + candidate=" + ctx.candidateRisk()
                            + " = " + total + " > limit=" + limit));
        }
        return Optional.empty();
    }

    @Override
    public int priority() { return 6; }
}
