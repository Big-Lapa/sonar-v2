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
 * Ported from v1 (ADR-0009), rewired onto {@link SymbolRulesCache} since v2 has no config-service.
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
            throw new IllegalArgumentException("Quantity is zero or negative after normalization for " + symbol);
        }
        if (rules.minQty() != null && normalized.compareTo(rules.minQty()) < 0) {
            throw new IllegalArgumentException(
                    "qty " + normalized + " < minQty " + rules.minQty() + " for " + symbol);
        }
        if (rules.minNotional() != null && price != null && price.signum() > 0) {
            BigDecimal notional = normalized.multiply(price);
            if (notional.compareTo(rules.minNotional()) < 0) {
                throw new IllegalArgumentException(
                        "notional " + notional + " < minNotional " + rules.minNotional() + " for " + symbol);
            }
        }
        if (normalized.compareTo(qty) != 0) {
            log.debug("QuantityNormalizer {}/{}: {} -> {} (step={})", venue, symbol, qty, normalized, rules.stepSize());
        }
        return normalized;
    }

    private BigDecimal floorToStep(BigDecimal value, BigDecimal step) {
        if (value == null) return null;
        if (step == null || step.signum() == 0) return value;
        BigDecimal[] dr = value.divideAndRemainder(step);
        return dr[0].multiply(step).setScale(Math.max(value.scale(), step.scale()), RoundingMode.DOWN);
    }
}
