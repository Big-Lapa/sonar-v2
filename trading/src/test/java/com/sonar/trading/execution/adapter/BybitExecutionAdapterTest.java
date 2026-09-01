package com.sonar.trading.execution.adapter;

import com.sonar.trading.config.TradingProperties;
import com.sonar.trading.exchange.bybit.BybitFuturesClient;
import com.sonar.trading.execution.gateway.BybitExecutionGateway;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
        return new BybitExecutionAdapter(Mockito.mock(BybitExecutionGateway.class), new TradingProperties(), client);
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
        // What placeStandaloneConditional's own remediation order actually looks like once
        // reflected back by /v5/order/realtime: stopOrderType="Stop" (generic), NOT "StopLoss".
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
        BybitExecutionAdapter adapter = new BybitExecutionAdapter(gateway, new TradingProperties(), client);

        adapter.cancelReduceOnlyOrder("BTCUSDT", false);

        verify(gateway).cancelOrder("BTCUSDT", "tp-order-1", null);
    }

    @Test
    void cancelReduceOnlyOrderIsNoOpWhenNothingMatches() {
        BybitFuturesClient client = Mockito.mock(BybitFuturesClient.class);
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("result", Map.of("list", List.of())));
        BybitExecutionGateway gateway = Mockito.mock(BybitExecutionGateway.class);
        BybitExecutionAdapter adapter = new BybitExecutionAdapter(gateway, new TradingProperties(), client);

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
}
