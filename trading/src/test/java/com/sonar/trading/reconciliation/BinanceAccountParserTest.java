package com.sonar.trading.reconciliation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceAccountParserTest {

    @Test
    void parseBalanceSkipsARowWithNoAssetLabelInsteadOfMatchingItByDefault() {
        // A row missing/blank "asset" (a partial or degraded response) must never be returned
        // under the requested asset's name just because it happened to come first.
        Map<String, Object> resp = Map.of("data", List.of(
                Map.of("balance", "999999"), // no "asset" field at all
                Map.of("asset", "USDT", "balance", "4691.05")));

        var balance = BinanceAccountParser.parseBalance(resp, "USDT");

        assertThat(balance).isPresent();
        assertThat(balance.get().asset()).isEqualTo("USDT");
        assertThat(balance.get().balance()).isEqualByComparingTo("4691.05");
    }

    @Test
    void parseBalanceFindsTheRequestedAssetRegardlessOfRowOrder() {
        Map<String, Object> resp = Map.of("data", List.of(
                Map.of("asset", "BNB", "balance", "1"),
                Map.of("asset", "USDT", "balance", "500")));

        var balance = BinanceAccountParser.parseBalance(resp, "USDT");

        assertThat(balance).isPresent();
        assertThat(balance.get().asset()).isEqualTo("USDT");
        assertThat(balance.get().balance()).isEqualByComparingTo("500");
    }

    @Test
    void parseBalanceFallsBackToTheFirstRowWhenNothingNamedMatches() {
        Map<String, Object> resp = Map.of("data", List.of(Map.of("asset", "BNB", "balance", "1")));

        var balance = BinanceAccountParser.parseBalance(resp, "USDT");

        assertThat(balance).isPresent();
        assertThat(balance.get().asset()).isEqualTo("BNB");
    }

    @Test
    void parseBalanceReturnsEmptyForNoRows() {
        assertThat(BinanceAccountParser.parseBalance(Map.of("data", List.of()), "USDT")).isEmpty();
    }

    @Test
    void parsePositionsDerivesLongShortFlatFromTheSignedAmount() {
        Map<String, Object> resp = Map.of("data", List.of(
                Map.of("symbol", "BTCUSDT", "positionAmt", "0.5"),
                Map.of("symbol", "ETHUSDT", "positionAmt", "-2"),
                Map.of("symbol", "SOLUSDT", "positionAmt", "0")));

        var positions = BinanceAccountParser.parsePositions(resp);

        assertThat(positions).hasSize(3);
        assertThat(positions).filteredOn(p -> p.symbol().equals("BTCUSDT")).extracting(p -> p.side())
                .containsExactly("LONG");
        assertThat(positions).filteredOn(p -> p.symbol().equals("ETHUSDT")).extracting(p -> p.side())
                .containsExactly("SHORT");
        assertThat(positions).filteredOn(p -> p.symbol().equals("SOLUSDT")).extracting(p -> p.side())
                .containsExactly("FLAT");
    }
}
