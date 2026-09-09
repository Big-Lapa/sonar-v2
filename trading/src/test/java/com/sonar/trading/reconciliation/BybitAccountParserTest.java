package com.sonar.trading.reconciliation;

import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangePosition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BybitAccountParserTest {

    // ---- Positions: GET /v5/position/list (result.list) ----

    @Test
    void parsesLongPositionFromSideField() {
        Map<String, Object> resp = Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT", "side", "Buy", "size", "0.5",
                        "avgPrice", "60000", "markPrice", "61000",
                        "unrealisedPnl", "500", "leverage", "10", "liqPrice", "50000"))));

        List<ExchangePosition> positions = BybitAccountParser.parsePositions(resp);

        assertThat(positions).hasSize(1);
        ExchangePosition p = positions.get(0);
        assertThat(p.symbol()).isEqualTo("BTCUSDT");
        assertThat(p.side()).isEqualTo("LONG");
        assertThat(p.qty()).isEqualByComparingTo("0.5");
        assertThat(p.avgPrice()).isEqualByComparingTo("60000");
        assertThat(p.markPrice()).isEqualByComparingTo("61000");
        assertThat(p.unrealizedPnl()).isEqualByComparingTo("500");
        assertThat(p.leverage()).isEqualTo(10);
        assertThat(p.liquidationPrice()).isEqualByComparingTo("50000");
        assertThat(p.isFlat()).isFalse();
    }

    @Test
    void blankSideWithZeroSizeIsFlat() {
        Map<String, Object> resp = Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "ETHUSDT", "side", "", "size", "0"))));

        List<ExchangePosition> positions = BybitAccountParser.parsePositions(resp);

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).side()).isEqualTo("FLAT");
        assertThat(positions.get(0).isFlat()).isTrue();
    }

    @Test
    void sellSideMapsToShort() {
        Map<String, Object> resp = Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT", "side", "Sell", "size", "0.2"))));

        assertThat(BybitAccountParser.parsePositions(resp).get(0).side()).isEqualTo("SHORT");
    }

    @Test
    void findPositionPrefersNonFlatRowForSameSymbol() {
        Map<String, Object> resp = Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT", "side", "", "size", "0"),
                Map.of("symbol", "BTCUSDT", "side", "Buy", "size", "1"))));

        Optional<ExchangePosition> found = BybitAccountParser.findPosition(resp, "btcusdt");

        assertThat(found).isPresent();
        assertThat(found.get().isFlat()).isFalse();
    }

    @Test
    void findPositionOnNullSymbolIsEmpty() {
        assertThat(BybitAccountParser.findPosition(Map.of(), null)).isEmpty();
    }

    // ---- Balance: GET /v5/account/wallet-balance (result.list[0].coin[]) ----

    @Test
    void parsesRequestedCoinFromNestedCoinArray() {
        Map<String, Object> resp = Map.of("result", Map.of("list", List.of(
                Map.of("totalEquity", "10000", "coin", List.of(
                        Map.of("coin", "USDT", "walletBalance", "9000", "equity", "9500",
                                "unrealisedPnl", "500", "availableToWithdraw", "8000",
                                "totalPositionIM", "1000"))))));

        Optional<ExchangeBalance> balance = BybitAccountParser.parseBalance(resp, "USDT");

        assertThat(balance).isPresent();
        ExchangeBalance b = balance.get();
        assertThat(b.asset()).isEqualTo("USDT");
        assertThat(b.balance()).isEqualByComparingTo("9000");
        assertThat(b.equity()).isEqualByComparingTo("9500");
        assertThat(b.unrealizedPnl()).isEqualByComparingTo("500");
        assertThat(b.available()).isEqualByComparingTo("8000");
        assertThat(b.used()).isEqualByComparingTo("1000");
    }

    @Test
    void fallsBackToAccountLevelTotalEquityWhenCoinEquityMissing() {
        Map<String, Object> resp = Map.of("result", Map.of("list", List.of(
                Map.of("totalEquity", "12345", "coin", List.of(
                        Map.of("coin", "USDT", "walletBalance", "9000"))))));

        Optional<ExchangeBalance> balance = BybitAccountParser.parseBalance(resp, "USDT");

        assertThat(balance).isPresent();
        assertThat(balance.get().equity()).isEqualByComparingTo("12345");
    }

    @Test
    void fallsBackToFirstCoinWhenRequestedAssetAbsent() {
        Map<String, Object> resp = Map.of("result", Map.of("list", List.of(
                Map.of("coin", List.of(Map.of("coin", "BTC", "walletBalance", "0.1"))))));

        Optional<ExchangeBalance> balance = BybitAccountParser.parseBalance(resp, "USDT");

        assertThat(balance).isPresent();
        assertThat(balance.get().asset()).isEqualTo("BTC");
    }

    @Test
    void emptyResultYieldsEmptyOptional() {
        assertThat(BybitAccountParser.parseBalance(Map.of(), "USDT")).isEmpty();
        assertThat(BybitAccountParser.parseBalance(null, "USDT")).isEmpty();
    }
}
