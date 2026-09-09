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

    @Test
    void floorForCloseRoundsDownToStep() {
        SymbolRules rules = new SymbolRules(
                new BigDecimal("0.001"), new BigDecimal("0.001"), BigDecimal.ZERO, new BigDecimal("0.01"));
        QuantityNormalizer normalizer = new QuantityNormalizer(cacheWith(rules));

        BigDecimal result = normalizer.floorForClose("BINANCE", "SOLUSDT", new BigDecimal("0.00625"), new BigDecimal("100"));

        assertThat(result).isEqualByComparingTo("0.006");
    }

    @Test
    void floorForCloseReturnsNullBelowExchangeMinQty() {
        SymbolRules rules = new SymbolRules(
                new BigDecimal("0.001"), new BigDecimal("0.01"), BigDecimal.ZERO, new BigDecimal("0.01"));
        QuantityNormalizer normalizer = new QuantityNormalizer(cacheWith(rules));

        // Floors cleanly to 0.005 (a whole step), but that's still below the exchange's 0.01 minQty
        // for a close order — must reject rather than round-trip to the exchange and fail there.
        BigDecimal result = normalizer.floorForClose("BINANCE", "SOLUSDT", new BigDecimal("0.005"), new BigDecimal("100"));

        assertThat(result).isNull();
    }

    @Test
    void floorForCloseReturnsNullBelowExchangeMinNotional() {
        SymbolRules rules = new SymbolRules(
                new BigDecimal("0.001"), new BigDecimal("0.001"), new BigDecimal("50"), new BigDecimal("0.01"));
        QuantityNormalizer normalizer = new QuantityNormalizer(cacheWith(rules));

        // 0.1 * 100 = 10 notional, below the 50 minNotional for a close order.
        BigDecimal result = normalizer.floorForClose("BINANCE", "SOLUSDT", new BigDecimal("0.1"), new BigDecimal("100"));

        assertThat(result).isNull();
    }

    @Test
    void floorForCloseIgnoresMinNotionalWhenPriceUnavailable() {
        SymbolRules rules = new SymbolRules(
                new BigDecimal("0.001"), new BigDecimal("0.001"), new BigDecimal("50"), new BigDecimal("0.01"));
        QuantityNormalizer normalizer = new QuantityNormalizer(cacheWith(rules));

        // No price to check notional against (e.g. a market close with no reference price handy) —
        // the step/minQty checks still apply, but minNotional is skipped rather than blocking.
        BigDecimal result = normalizer.floorForClose("BINANCE", "SOLUSDT", new BigDecimal("0.1"), null);

        assertThat(result).isEqualByComparingTo("0.1");
    }
}
