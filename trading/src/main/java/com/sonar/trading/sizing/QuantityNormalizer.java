package com.sonar.trading.sizing;

import com.sonar.trading.config.SymbolRules;
import com.sonar.trading.config.SymbolRulesCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Clamps quantity to exchange constraints (4.2 sizing, step 5): stepSize, minQty, minNotional.
 *
 * <p>Below the exchange minimum, this BUMPS UP to the minimum rather than rejecting — decided
 * with the user 2026-08-21 after a live run showed a far SL (small risk-based qty) getting
 * rejected outright even though the position was perfectly tradeable, just at a higher actual $
 * risk than the plan. The caller ({@link RiskBasedPositionSizer}) is responsible for checking
 * that bump doesn't blow past the account's OWN caps (max_position_notional, max_leverage) —
 * this class only knows the exchange's floor, not the account's risk profile.
 */
@Component
public class QuantityNormalizer {

    private static final Logger log = LoggerFactory.getLogger(QuantityNormalizer.class);

    private final SymbolRulesCache symbolRules;

    public QuantityNormalizer(SymbolRulesCache symbolRules) {
        this.symbolRules = symbolRules;
    }

    public BigDecimal normalize(String venue, String symbol, BigDecimal qty, BigDecimal price) {
        SymbolRules rules = symbolRules.get(venue, symbol);
        BigDecimal normalized = floorToStep(qty, rules.stepSize());
        if (normalized == null || normalized.signum() <= 0) {
            normalized = rules.stepSize() != null && rules.stepSize().signum() > 0 ? rules.stepSize() : qty;
        }

        if (rules.minQty() != null && normalized.compareTo(rules.minQty()) < 0) {
            normalized = ceilToStep(rules.minQty(), rules.stepSize());
        }
        if (rules.minNotional() != null && price != null && price.signum() > 0) {
            BigDecimal notional = normalized.multiply(price);
            if (notional.compareTo(rules.minNotional()) < 0) {
                BigDecimal needed = rules.minNotional().divide(price, 10, RoundingMode.UP);
                normalized = ceilToStep(needed, rules.stepSize());
            }
        }

        if (normalized.compareTo(qty) != 0) {
            log.debug("QuantityNormalizer {}/{}: {} -> {} (step={}, minQty={}, minNotional={})",
                    venue, symbol, qty, normalized, rules.stepSize(), rules.minQty(), rules.minNotional());
        }
        return normalized;
    }

    private BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        if (value == null) return null;
        if (step == null || step.signum() == 0) return value;
        BigDecimal[] dr = value.divideAndRemainder(step);
        return dr[0].multiply(step).setScale(Math.max(value.scale(), step.scale()), RoundingMode.DOWN);
    }

    private BigDecimal ceilToStep(BigDecimal value, BigDecimal step) {
        if (step == null || step.signum() == 0) return value;
        BigDecimal[] dr = value.divideAndRemainder(step);
        BigDecimal floored = dr[0].multiply(step);
        if (dr[1].signum() == 0) {
            return floored.setScale(step.scale(), RoundingMode.DOWN);
        }
        return floored.add(step).setScale(step.scale(), RoundingMode.DOWN);
    }
}
