package com.sonar.trading.exchange.binance.http;

import com.sonar.trading.exchange.binance.BinanceOrderSide;
import com.sonar.trading.exchange.binance.BinanceOrderType;
import com.sonar.trading.exchange.binance.BinancePlaceOrderRequest;
import com.sonar.trading.exchange.binance.BinancePositionSide;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code buildPlaceOrderQuery} is package-private specifically so it can be unit-tested without
 * HTTP — same rationale as {@code BybitFuturesHttpService.buildPlaceOrderBody}.
 */
class BinanceFuturesHttpServiceTest {

    @Test
    void plainMarketEntryCarriesQuantityAndOneWayPositionSide() {
        BinancePlaceOrderRequest req = new BinancePlaceOrderRequest("BTCUSDT", BinanceOrderSide.BUY,
                BinancePositionSide.BOTH, BinanceOrderType.MARKET, "0.5", null, null, "cid-1", false);

        Map<String, String> q = BinanceFuturesHttpService.buildPlaceOrderQuery(req);

        assertThat(q).containsEntry("symbol", "BTCUSDT");
        assertThat(q).containsEntry("side", "BUY");
        assertThat(q).containsEntry("type", "MARKET");
        assertThat(q).containsEntry("quantity", "0.5");
        assertThat(q).containsEntry("positionSide", "BOTH");
        assertThat(q).doesNotContainKey("reduceOnly");
        assertThat(q).doesNotContainKey("closePosition");
    }

    @Test
    void limitOrderCarriesPriceAndTimeInForce() {
        BinancePlaceOrderRequest req = new BinancePlaceOrderRequest("ETHUSDT", BinanceOrderSide.SELL,
                BinancePositionSide.BOTH, BinanceOrderType.LIMIT, "1", "2500.5", null, "cid-2", false);

        Map<String, String> q = BinanceFuturesHttpService.buildPlaceOrderQuery(req);

        assertThat(q).containsEntry("price", "2500.5");
        assertThat(q).containsEntry("timeInForce", "GTC");
    }

    @Test
    void reduceOnlyCloseLegCarriesTheFlag() {
        BinancePlaceOrderRequest req = new BinancePlaceOrderRequest("BTCUSDT", BinanceOrderSide.SELL,
                BinancePositionSide.BOTH, BinanceOrderType.MARKET, "0.5", null, null, "cid-3", true);

        Map<String, String> q = BinanceFuturesHttpService.buildPlaceOrderQuery(req);

        assertThat(q).containsEntry("reduceOnly", "true");
    }

    @Test
    void closePositionConditionalOrderOmitsQuantityAndReduceOnly() {
        BinancePlaceOrderRequest req = new BinancePlaceOrderRequest("BTCUSDT", BinanceOrderSide.SELL,
                BinancePositionSide.BOTH, BinanceOrderType.STOP_MARKET, null, null, null, "cid-4",
                false, "58000", true, "MARK_PRICE");

        Map<String, String> q = BinanceFuturesHttpService.buildPlaceOrderQuery(req);

        assertThat(q).containsEntry("closePosition", "true");
        assertThat(q).containsEntry("stopPrice", "58000");
        assertThat(q).containsEntry("workingType", "MARK_PRICE");
        assertThat(q).doesNotContainKey("quantity");
        assertThat(q).doesNotContainKey("reduceOnly");
    }

    @Test
    void nonClosePositionOrderWithNullQuantityFailsAtBuildTimeRatherThanSendingAnEmptyParameter() {
        // A null quantity here would otherwise go out as the literal query-string "quantity=" —
        // BinanceRequestSigner's encoder turns null into an empty string rather than dropping the
        // key — surfacing as an opaque -1102 from the exchange instead of failing at the call site
        // that forgot to compute a size.
        BinancePlaceOrderRequest req = new BinancePlaceOrderRequest("BTCUSDT", BinanceOrderSide.SELL,
                BinancePositionSide.BOTH, BinanceOrderType.TAKE_PROFIT_MARKET, null, null, null, "cid-5",
                true, "65000", false, null);

        assertThatThrownBy(() -> BinanceFuturesHttpService.buildPlaceOrderQuery(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quantity");
    }

    @Test
    void explicitQuantityPartialTakeProfitIsNotClosePositionAndKeepsReduceOnly() {
        BinancePlaceOrderRequest req = new BinancePlaceOrderRequest("BTCUSDT", BinanceOrderSide.SELL,
                BinancePositionSide.BOTH, BinanceOrderType.TAKE_PROFIT_MARKET, "0.1", null, null, "cid-6",
                true, "62000", false, "MARK_PRICE");

        Map<String, String> q = BinanceFuturesHttpService.buildPlaceOrderQuery(req);

        assertThat(q).containsEntry("quantity", "0.1");
        assertThat(q).containsEntry("reduceOnly", "true");
        assertThat(q).doesNotContainKey("closePosition");
    }

    // ---- dedupKey: getIncome pagination overlap dedup ----

    @Test
    void dedupKeyPrefersTranIdWhenPresent() {
        Map<String, Object> row = Map.of("tranId", "12345", "time", "1000", "income", "1.5");

        assertThat(BinanceFuturesHttpService.dedupKey(row)).isEqualTo("tranId:12345");
    }

    @Test
    void dedupKeySameTranIdMatchesAcrossTwoOtherwiseDifferentRows() {
        // Same underlying transaction fetched from two overlapping pages must dedup identically.
        Map<String, Object> rowPageOne = Map.of("tranId", "12345", "time", "1000", "income", "1.5");
        Map<String, Object> rowPageTwo = Map.of("tranId", "12345", "time", "1000", "income", "1.5", "extra", "field");

        assertThat(BinanceFuturesHttpService.dedupKey(rowPageOne))
                .isEqualTo(BinanceFuturesHttpService.dedupKey(rowPageTwo));
    }

    @Test
    void dedupKeyFallsBackToACompositeKeyWhenTranIdIsAbsent() {
        // The cursor deliberately re-fetches the last page's exact millisecond (no +1) — a row
        // with no tranId to dedup by must not be counted from both pages, which would double its
        // income into the sum (the wrong direction for a drawdown gate: too lenient, not too strict).
        Map<String, Object> row1 = Map.of("time", "1000", "symbol", "BTCUSDT", "incomeType", "FUNDING_FEE", "income", "0.5");
        Map<String, Object> row2 = Map.of("time", "1000", "symbol", "BTCUSDT", "incomeType", "FUNDING_FEE", "income", "0.5");
        Map<String, Object> differentRow = Map.of("time", "1000", "symbol", "ETHUSDT", "incomeType", "FUNDING_FEE", "income", "0.5");

        assertThat(BinanceFuturesHttpService.dedupKey(row1)).isEqualTo(BinanceFuturesHttpService.dedupKey(row2));
        assertThat(BinanceFuturesHttpService.dedupKey(row1)).isNotEqualTo(BinanceFuturesHttpService.dedupKey(differentRow));
    }

    @Test
    void dedupKeyHandlesANonMapRowWithoutThrowing() {
        assertThat(BinanceFuturesHttpService.dedupKey("not-a-map")).isNotNull();
    }
}
