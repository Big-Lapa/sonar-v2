package com.sonar.trading.execution.adapter;

import com.sonar.trading.config.TradingProperties;
import com.sonar.trading.exchange.bybit.BybitFuturesClient;
import com.sonar.trading.exchange.bybit.BybitTriggerBy;
import com.sonar.trading.execution.gateway.BybitExecutionGateway;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR review fix: this adapter shipped with zero coverage, and that's exactly where the 4 critical
 * bugs from the Bybit review landed (position-level attached TP/SL invisible to hasOpenStopOrder,
 * matching a standalone conditional order's own {@code stopOrderType} substring instead of its
 * {@code triggerDirection}, wrong price-field preference in getMarkPrice). These tests pin the
 * fixed behavior against realistic Bybit V5 response shapes.
 */
class BybitExecutionAdapterTest {

    private static BybitExecutionAdapter adapter(BybitFuturesClient client) {
        return new BybitExecutionAdapter("bybit-demo", Mockito.mock(BybitExecutionGateway.class), new TradingProperties(), client);
    }

    // ---- hasOpenStopOrder: position-level attached SL (TP/SL set on the entry order itself) ----

    @Test
    void hasOpenStopOrderIsTrueFromPositionLevelStopLossWithoutCheckingOpenOrders() {
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getPositions("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT", "stopLoss", "58000")))));

        boolean result = adapter(client).hasOpenStopOrder("BTCUSDT");

        assertThat(result).isTrue();
        verify(client, never()).getOpenOrders(any());
    }

    @Test
    void hasOpenStopOrderIgnoresBlankOrZeroPositionLevelStopLoss() {
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getPositions("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT", "stopLoss", "0")))));
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of())));

        boolean result = adapter(client).hasOpenStopOrder("BTCUSDT");

        assertThat(result).isFalse();
        verify(client).getOpenOrders("BTCUSDT"); // fell through to the open-orders check
    }

    // ---- hasOpenStopOrder: standalone conditional order via triggerDirection, not stopOrderType substring ----

    @Test
    void findsOwnStandaloneRemediationStopByTriggerDirection() {
        // A leftover standalone conditional (older cancel-then-place path, or placed by hand in
        // the terminal) as /v5/order/realtime reflects it: stopOrderType="Stop", NOT "StopLoss".
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getPositions("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of())));
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT", "stopOrderType", "Stop", "triggerPrice", "58000", "triggerDirection", "2")))));

        boolean result = adapter(client).hasOpenStopOrder("BTCUSDT");

        assertThat(result).isTrue();
    }

    @Test
    void takeProfitDirectionOrderIsNotMistakenForAStop() {
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getPositions("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of())));
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT", "stopOrderType", "Stop", "triggerPrice", "70000", "triggerDirection", "1")))));

        boolean result = adapter(client).hasOpenStopOrder("BTCUSDT");

        assertThat(result).isFalse();
    }

    @Test
    void plainLimitOrderWithoutTriggerPriceIsNeverConditional() {
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getPositions("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of())));
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT", "orderType", "Limit")))));

        assertThat(adapter(client).hasOpenStopOrder("BTCUSDT")).isFalse();
    }

    @Test
    void fallsBackToStopOrderTypeSubstringWhenTriggerDirectionIsAbsent() {
        // A conditional order placed by other tooling (tpslMode) rather than this adapter's own
        // standalone-trigger path — carries the specific StopLoss/TakeProfit stopOrderType values.
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getPositions("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of())));
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT", "stopOrderType", "StopLoss")))));

        assertThat(adapter(client).hasOpenStopOrder("BTCUSDT")).isTrue();
    }

    // ---- cancelReduceOnlyOrder: same triggerDirection-based matching ----

    @Test
    void cancelReduceOnlyOrderFindsTakeProfitByTriggerDirectionOne() {
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT", "orderId", "tp-order-1", "stopOrderType", "Stop",
                        "triggerPrice", "70000", "triggerDirection", "1")))));
        BybitExecutionGateway gateway = Mockito.mock(BybitExecutionGateway.class);
        BybitExecutionAdapter adapter = new BybitExecutionAdapter("bybit-demo", gateway, new TradingProperties(), client);

        adapter.cancelReduceOnlyOrder("BTCUSDT", false);

        verify(gateway).cancelOrder("BTCUSDT", "tp-order-1", null);
    }

    @Test
    void cancelReduceOnlyOrderIsNoOpWhenNothingMatches() {
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of())));
        BybitExecutionGateway gateway = Mockito.mock(BybitExecutionGateway.class);
        BybitExecutionAdapter adapter = new BybitExecutionAdapter("bybit-demo", gateway, new TradingProperties(), client);

        adapter.cancelReduceOnlyOrder("BTCUSDT", true);

        verify(gateway, never()).cancelOrder(any(), any(), any());
    }

    // ---- getMarkPrice: must prefer markPrice (what conditional orders trigger against) ----

    @Test
    void getMarkPricePrefersMarkPriceOverLastPrice() {
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getPrice("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT", "lastPrice", "60123.5", "markPrice", "60100.0")))));

        var price = adapter(client).getMarkPrice("BTCUSDT");

        assertThat(price).isEqualByComparingTo("60100.0");
    }

    @Test
    void getMarkPriceFallsBackToLastPriceWhenMarkPriceMissing() {
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getPrice("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT", "lastPrice", "60123.5")))));

        var price = adapter(client).getMarkPrice("BTCUSDT");

        assertThat(price).isEqualByComparingTo("60123.5");
    }

    // ---- Moving a stop goes through position-level trading-stop, never a standalone order ----

    @Test
    void placeReduceOnlyStopSetsPositionLevelStopLossAndPlacesNoOrder() {
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        BybitExecutionGateway gateway = Mockito.mock(BybitExecutionGateway.class);
        BybitExecutionAdapter adapter = new BybitExecutionAdapter("bybit-demo", gateway, new TradingProperties(), client);

        adapter.placeReduceOnlyStop("BTCUSDT", new BigDecimal("0.5"), new BigDecimal("60000.0"));

        // takeProfit stays null: the call must not wipe the position's TP as a side effect.
        verify(gateway).setTradingStop("BTCUSDT", "60000", null, BybitTriggerBy.MarkPrice);
        // A standalone conditional order alongside the position-level stop is the duplicate-stop
        // bug this whole path exists to prevent.
        verify(gateway, never()).placeOrder(any(), any());
    }

    @Test
    void placeReduceOnlyTakeProfitSetsPositionLevelTakeProfitOnly() {
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        BybitExecutionGateway gateway = Mockito.mock(BybitExecutionGateway.class);
        BybitExecutionAdapter adapter = new BybitExecutionAdapter("bybit-demo", gateway, new TradingProperties(), client);

        adapter.placeReduceOnlyTakeProfit("BTCUSDT", new BigDecimal("0.5"), new BigDecimal("70000"));

        verify(gateway).setTradingStop("BTCUSDT", null, "70000", BybitTriggerBy.MarkPrice);
        verify(gateway, never()).placeOrder(any(), any());
    }

    @Test
    void placeReduceOnlyStopUsesConfiguredTriggerBy() {
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        BybitExecutionGateway gateway = Mockito.mock(BybitExecutionGateway.class);
        TradingProperties props = new TradingProperties();
        props.getBybit().setTriggerBy("LastPrice");
        BybitExecutionAdapter adapter = new BybitExecutionAdapter("bybit-demo", gateway, props, client);

        adapter.placeReduceOnlyStop("BTCUSDT", new BigDecimal("0.5"), new BigDecimal("60000"));

        verify(gateway).setTradingStop("BTCUSDT", "60000", null, BybitTriggerBy.LastPrice);
    }

    @Test
    void cancelReduceOnlyOrderLeavesPositionLevelStopForThePlaceCallToOverwrite() {
        // No conditional orders open — the normal state once a stop lives on the position. The
        // cancel half of a /be must then do nothing at all: clearing the position-level stop here
        // would reopen the unprotected window that trading-stop's atomic overwrite closes.
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of())));
        BybitExecutionGateway gateway = Mockito.mock(BybitExecutionGateway.class);
        BybitExecutionAdapter adapter = new BybitExecutionAdapter("bybit-demo", gateway, new TradingProperties(), client);

        adapter.cancelReduceOnlyOrder("BTCUSDT", true);

        verify(gateway, never()).setTradingStop(any(), any(), any(), any());
        verify(gateway, never()).cancelOrder(any(), any(), any());
    }

    // ---- hasOpenStopOrder retries only while the position isn't visible yet ----

    @Test
    void hasOpenStopOrderRetriesWhilePositionListIsEmptyThenSeesTheStop() {
        // /v5/position/list lagging the fill that just created the position — StopGuard runs
        // immediately after entry, so without the retry it reads "no stop" on a protected position.
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getPositions("BTCUSDT")).thenReturn(
                Map.of("result", Map.of("list", List.of())),
                Map.of("result", Map.of("list", List.of(
                        Map.of("symbol", "BTCUSDT", "stopLoss", "58000")))));

        boolean result = adapter(client).hasOpenStopOrder("BTCUSDT");

        assertThat(result).isTrue();
        verify(client, never()).getOpenOrders(any());
    }

    @Test
    void hasOpenStopOrderDoesNotRetryAVisiblePositionThatSimplyHasNoStop() {
        // Position IS visible and carries no stop — a real answer, nothing to wait for. Retrying
        // here would just delay StopGuard's remediation of a genuinely unprotected position.
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getPositions("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT", "size", "0.5", "stopLoss", "")))));
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of())));

        assertThat(adapter(client).hasOpenStopOrder("BTCUSDT")).isFalse();
        verify(client, Mockito.times(1)).getPositions("BTCUSDT");
    }
}
