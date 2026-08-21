package com.sonar.trading.sizing;

import com.sonar.trading.config.SymbolRules;
import com.sonar.trading.config.SymbolRulesCache;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** ADR-0009 exchange clamp, rewired onto {@link SymbolRulesCache} (4.0: no config-service in v2). */
class QuantityNormalizerTest {

    private static SymbolRulesCache cacheWith(SymbolRules rules) {
        SymbolRulesCache cache = Mockito.mock(SymbolRulesCache.class);
        when(cache.get(anyString(), anyString())).thenReturn(rules);
        return cache;
    }

    @Test
    void flooredToStepSizeAndPassesMinimums() {
        SymbolRules rules = new SymbolRules(
                new BigDecimal("0.01"), new BigDecimal("0.01"), new BigDecimal("5"), new BigDecimal("0.01"));
        QuantityNormalizer normalizer = new QuantityNormalizer(cacheWith(rules));

        BigDecimal result = normalizer.normalize("BINANCE", "SOLUSDT",
                new BigDecimal("7.9139"), new BigDecimal("100"));

        assertThat(result).isEqualByComparingTo("7.91");
    }

    /** 2026-08-21: below minQty now bumps UP to the exchange floor instead of rejecting — a far
     *  SL naturally computes a small risk-based qty, and that's a tradeable position, just at a
     *  higher actual $ risk than planned. See {@link RiskBasedPositionSizer} for the account-cap
     *  boundary that still applies on top of this. */
    @Test
    void bumpsUpToMinQtyInsteadOfRejecting() {
        SymbolRules rules = new SymbolRules(
                new BigDecimal("0.001"), new BigDecimal("1"), new BigDecimal("5"), new BigDecimal("0.01"));
        QuantityNormalizer normalizer = new QuantityNormalizer(cacheWith(rules));

        BigDecimal result = normalizer.normalize("BINANCE", "SOLUSDT",
                new BigDecimal("0.5"), new BigDecimal("100"));

        assertThat(result).isEqualByComparingTo("1");
    }

    @Test
    void bumpsUpToMinNotionalInsteadOfRejecting() {
        SymbolRules rules = new SymbolRules(
                new BigDecimal("0.001"), new BigDecimal("0.001"), new BigDecimal("50"), new BigDecimal("0.01"));
        QuantityNormalizer normalizer = new QuantityNormalizer(cacheWith(rules));

        // notional 0.1*100=10 < minNotional 50 -> needs qty >= 0.5 to clear 50 at price 100
        BigDecimal result = normalizer.normalize("BINANCE", "SOLUSDT",
                new BigDecimal("0.1"), new BigDecimal("100"));

        assertThat(result).isEqualByComparingTo("0.5");
        assertThat(result.multiply(new BigDecimal("100"))).isGreaterThanOrEqualTo(new BigDecimal("50"));
    }
}
