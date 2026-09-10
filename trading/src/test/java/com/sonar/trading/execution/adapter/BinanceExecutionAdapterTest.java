package com.sonar.trading.execution.adapter;

import com.sonar.trading.config.TradingProperties;
import com.sonar.trading.exchange.binance.BinanceFuturesClient;
import com.sonar.trading.exchange.binance.BinancePlaceOrderRequest;
import com.sonar.trading.exchange.dto.AdapterOpenOrder;
import com.sonar.trading.exchange.dto.AdapterOrderRequest;
import com.sonar.trading.exchange.dto.StopInspection;
import com.sonar.trading.exchange.dto.TpSlAttachment;
import com.sonar.trading.exchange.model.AdapterOrderSide;
import com.sonar.trading.exchange.model.AdapterOrderState;
import com.sonar.trading.exchange.model.AdapterOrderType;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.execution.gateway.BinanceExecutionGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mirrors {@code BybitExecutionAdapterTest}: this adapter shipped with zero coverage (parked in
 * {@code _stage3-pending} since the v1->v2 migration), so these tests pin the behaviour a
 * from-scratch implementation must get right — the OCO-equivalent {@code closePosition=true}
 * conditional exits, {@code /fapi/v1/openOrders}-based stop inspection (unlike Bybit's
 * position-level fields, Binance's protective orders are ordinary orders), and the pre-entry
 * conditional-order sweep that prevents a triggered TP's sibling SL from surviving into the next
 * position (zombie stop).
 */
class BinanceExecutionAdapterTest {

    private static BinanceExecutionAdapter adapter(BinanceFuturesClient client, BinanceExecutionGateway gateway) {
        return new BinanceExecutionAdapter("binance-demo", gateway, new TradingProperties(), client);
    }

    private static BinanceExecutionGateway gatewayMock() {
        return Mockito.mock(BinanceExecutionGateway.class);
    }

    // ---- placeOrder: pre-entry sweep + leverage + conditional exits ----

    @Test
    void placeOrderSweepsStaleConditionalOrdersAndSetsLeverageBeforeAnEntry() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        when(gateway.placeOrder(any(), any())).thenReturn(Map.of("orderId", "1", "status", "NEW"));
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        AdapterOrderRequest request = new AdapterOrderRequest("BTCUSDT", AdapterOrderSide.BUY,
                AdapterOrderType.MARKET, new BigDecimal("0.5"), null, "cid-1", OrderIntent.ENTRY, null, null, 10);

        adapter.placeOrder(request);

        verify(gateway).cancelAllOpenOrders("BTCUSDT");
        verify(gateway).setLeverage("BTCUSDT", 10);
    }

    @Test
    void placeOrderDoesNotSweepOrSetLeverageOnACloseReduceLeg() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        when(gateway.placeOrder(any(), any())).thenReturn(Map.of("orderId", "1", "status", "FILLED"));
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        AdapterOrderRequest request = new AdapterOrderRequest("BTCUSDT", AdapterOrderSide.SELL,
                AdapterOrderType.MARKET, new BigDecimal("0.5"), null, "cid-2", OrderIntent.CLOSE_REDUCE);

        adapter.placeOrder(request);

        verify(gateway, never()).cancelAllOpenOrders(anyString());
        verify(gateway, never()).setLeverage(anyString(), Mockito.anyInt());
    }

    @Test
    void placeOrderAttachesStandaloneCloseAllConditionalExitsAfterAFilledEntry() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        when(gateway.placeOrder(any(), any()))
                .thenReturn(Map.of("orderId", "1", "status", "FILLED", "executedQty", "0.5", "avgPrice", "60000"));
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        TpSlAttachment tpSl = new TpSlAttachment(new BigDecimal("65000"), new BigDecimal("58000"));
        AdapterOrderRequest request = new AdapterOrderRequest("BTCUSDT", AdapterOrderSide.BUY,
                AdapterOrderType.MARKET, new BigDecimal("0.5"), null, "cid-3", OrderIntent.ENTRY, null, tpSl);

        adapter.placeOrder(request);

        ArgumentCaptor<BinancePlaceOrderRequest> captor = ArgumentCaptor.forClass(BinancePlaceOrderRequest.class);
        // entry + SL + TP = 3 placeOrder calls
        verify(gateway, Mockito.times(3)).placeOrder(captor.capture(), any());
        List<BinancePlaceOrderRequest> conditionalOrders = captor.getAllValues().subList(1, 3);
        assertThat(conditionalOrders).allSatisfy(o -> {
            assertThat(o.closePosition()).isTrue();
            assertThat(o.side().name()).isEqualTo("SELL"); // opposite of the BUY entry
        });
        assertThat(conditionalOrders).anySatisfy(o ->
                assertThat(o.stopPrice()).isEqualTo("58000"));
        assertThat(conditionalOrders).anySatisfy(o ->
                assertThat(o.stopPrice()).isEqualTo("65000"));
    }

    @Test
    void placeOrderSkipsThePreEntrySweepWhenTheSymbolAlreadyHasAnOpenPosition() {
        // A second entry on an already-open position (pyramiding, or a retried idea) must not
        // sweep away that position's own live conditional stop before the new order even lands.
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        when(gateway.getPositions("BTCUSDT")).thenReturn(Map.of("data", List.of(
                Map.of("symbol", "BTCUSDT", "positionAmt", "0.5"))));
        when(gateway.placeOrder(any(), any())).thenReturn(Map.of("orderId", "1", "status", "NEW"));
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        AdapterOrderRequest request = new AdapterOrderRequest("BTCUSDT", AdapterOrderSide.BUY,
                AdapterOrderType.MARKET, new BigDecimal("0.5"), null, "cid-5", OrderIntent.ENTRY, null, null, 10);

        adapter.placeOrder(request);

        verify(gateway, never()).cancelAllOpenOrders(anyString());
        verify(gateway).setLeverage("BTCUSDT", 10); // leverage is independent of the sweep guard
    }

    @Test
    void placeOrderSweepsWhenTheSymbolIsFlat() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        when(gateway.getPositions("BTCUSDT")).thenReturn(Map.of("data", List.of(
                Map.of("symbol", "BTCUSDT", "positionAmt", "0"))));
        when(gateway.placeOrder(any(), any())).thenReturn(Map.of("orderId", "1", "status", "NEW"));
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        AdapterOrderRequest request = new AdapterOrderRequest("BTCUSDT", AdapterOrderSide.BUY,
                AdapterOrderType.MARKET, new BigDecimal("0.5"), null, "cid-6", OrderIntent.ENTRY, null, null, 10);

        adapter.placeOrder(request);

        verify(gateway).cancelAllOpenOrders("BTCUSDT");
    }

    @Test
    void placeOrderTreatsAFailedPositionCheckAsPositionExistsAndSkipsTheSweep() {
        // Fail-safe: an unreadable position state must not be treated as "definitely flat" —
        // that would risk sweeping a real position's protection based on a read that never
        // actually confirmed it was safe to.
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        when(gateway.getPositions("BTCUSDT")).thenThrow(new RuntimeException("rate limited"));
        when(gateway.placeOrder(any(), any())).thenReturn(Map.of("orderId", "1", "status", "NEW"));
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        AdapterOrderRequest request = new AdapterOrderRequest("BTCUSDT", AdapterOrderSide.BUY,
                AdapterOrderType.MARKET, new BigDecimal("0.5"), null, "cid-7", OrderIntent.ENTRY, null, null, 10);

        adapter.placeOrder(request);

        verify(gateway, never()).cancelAllOpenOrders(anyString());
    }

    // ---- partial TP1: an explicit reduce-only leg, not a silently dropped feature ----

    @Test
    void placeOrderPlacesAnExplicitPartialTp1LegWhenTheAttachmentHasOne() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        when(gateway.getPositions("BTCUSDT")).thenReturn(Map.of("data", List.of()));
        when(gateway.placeOrder(any(), any()))
                .thenReturn(Map.of("orderId", "1", "status", "FILLED", "executedQty", "0.5", "avgPrice", "60000"));
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        TpSlAttachment tpSl = new TpSlAttachment(new BigDecimal("65000"), new BigDecimal("58000"),
                new BigDecimal("62000"), new BigDecimal("0.2"));
        AdapterOrderRequest request = new AdapterOrderRequest("BTCUSDT", AdapterOrderSide.BUY,
                AdapterOrderType.MARKET, new BigDecimal("0.5"), null, "cid-8", OrderIntent.ENTRY, null, tpSl);

        adapter.placeOrder(request);

        ArgumentCaptor<BinancePlaceOrderRequest> captor = ArgumentCaptor.forClass(BinancePlaceOrderRequest.class);
        // entry + SL + TP + TP1 = 4 placeOrder calls
        verify(gateway, Mockito.times(4)).placeOrder(captor.capture(), any());
        BinancePlaceOrderRequest tp1 = captor.getAllValues().get(3);
        assertThat(tp1.type().name()).isEqualTo("TAKE_PROFIT_MARKET");
        assertThat(tp1.closePosition()).isFalse(); // explicit quantity, not a full-position close
        assertThat(tp1.reduceOnly()).isTrue();
        assertThat(tp1.quantity()).isEqualTo("0.2");
        assertThat(tp1.stopPrice()).isEqualTo("62000");
    }

    @Test
    void placeOrderScalesThePartialTp1QuantityToAPartialFill() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        when(gateway.getPositions("BTCUSDT")).thenReturn(Map.of("data", List.of()));
        // Only half the requested quantity actually filled.
        when(gateway.placeOrder(any(), any()))
                .thenReturn(Map.of("orderId", "1", "status", "PARTIALLY_FILLED", "executedQty", "0.25", "avgPrice", "60000"));
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        TpSlAttachment tpSl = new TpSlAttachment(new BigDecimal("65000"), new BigDecimal("58000"),
                new BigDecimal("62000"), new BigDecimal("0.2"));
        AdapterOrderRequest request = new AdapterOrderRequest("BTCUSDT", AdapterOrderSide.BUY,
                AdapterOrderType.MARKET, new BigDecimal("0.5"), null, "cid-9", OrderIntent.ENTRY, null, tpSl);

        adapter.placeOrder(request);

        ArgumentCaptor<BinancePlaceOrderRequest> captor = ArgumentCaptor.forClass(BinancePlaceOrderRequest.class);
        verify(gateway, Mockito.times(4)).placeOrder(captor.capture(), any());
        BinancePlaceOrderRequest tp1 = captor.getAllValues().get(3);
        // requested tp1Quantity (0.2) scaled by filled/requested (0.25/0.5 = 0.5) = 0.1
        assertThat(new BigDecimal(tp1.quantity())).isEqualByComparingTo("0.1");
    }

    @Test
    void placeOrderSkipsThePartialTp1LegWhenTheEntryHasNoFillYet() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        when(gateway.getPositions("BTCUSDT")).thenReturn(Map.of("data", List.of()));
        when(gateway.placeOrder(any(), any())).thenReturn(Map.of("orderId", "1", "status", "NEW"));
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        TpSlAttachment tpSl = new TpSlAttachment(new BigDecimal("65000"), new BigDecimal("58000"),
                new BigDecimal("62000"), new BigDecimal("0.2"));
        AdapterOrderRequest request = new AdapterOrderRequest("BTCUSDT", AdapterOrderSide.BUY,
                AdapterOrderType.MARKET, new BigDecimal("0.5"), null, "cid-10", OrderIntent.ENTRY, null, tpSl);

        adapter.placeOrder(request);

        // entry + SL + TP only — no TP1, since NEW/unfilled means the fill quantity is unknown.
        verify(gateway, Mockito.times(3)).placeOrder(any(), any());
    }

    @Test
    void placeOrderSkipsConditionalExitsWhenTheEntryWasRejected() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        when(gateway.placeOrder(any(), any())).thenReturn(Map.of("status", "REJECTED"));
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        TpSlAttachment tpSl = new TpSlAttachment(new BigDecimal("65000"), new BigDecimal("58000"));
        AdapterOrderRequest request = new AdapterOrderRequest("BTCUSDT", AdapterOrderSide.BUY,
                AdapterOrderType.MARKET, new BigDecimal("0.5"), null, "cid-4", OrderIntent.ENTRY, null, tpSl);

        adapter.placeOrder(request);

        verify(gateway, Mockito.times(1)).placeOrder(any(), any()); // entry only, no exit legs
    }

    // ---- hasOpenStopOrder / inspectStops: read from /fapi/v1/openOrders (ordinary orders) ----

    @Test
    void hasOpenStopOrderIsTrueForAnOpenStopMarketOrder() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("data", List.of(
                Map.of("symbol", "BTCUSDT", "type", "STOP_MARKET", "stopPrice", "58000"))));

        boolean result = adapter(client, gatewayMock()).hasOpenStopOrder("BTCUSDT");

        assertThat(result).isTrue();
    }

    @Test
    void hasOpenStopOrderIsFalseWhenOnlyATakeProfitOrderIsOpen() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("data", List.of(
                Map.of("symbol", "BTCUSDT", "type", "TAKE_PROFIT_MARKET", "stopPrice", "65000"))));

        assertThat(adapter(client, gatewayMock()).hasOpenStopOrder("BTCUSDT")).isFalse();
    }

    @Test
    void hasOpenStopOrderIsFalseWhenNoOrdersAreOpen() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("data", List.of()));

        assertThat(adapter(client, gatewayMock()).hasOpenStopOrder("BTCUSDT")).isFalse();
    }

    @Test
    void inspectStopsReadsBothLevelsFromOpenConditionalOrders() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("data", List.of(
                Map.of("symbol", "BTCUSDT", "type", "STOP_MARKET", "stopPrice", "58000"),
                Map.of("symbol", "BTCUSDT", "type", "TAKE_PROFIT_MARKET", "stopPrice", "65000"))));

        StopInspection stops = adapter(client, gatewayMock()).inspectStops("BTCUSDT");

        assertThat(stops.hasStop()).isTrue();
        assertThat(stops.stopLoss()).isEqualByComparingTo("58000");
        assertThat(stops.takeProfit()).isEqualByComparingTo("65000");
    }

    @Test
    void inspectStopsReportsNoStopWhenNoConditionalOrdersAreOpen() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("data", List.of()));

        StopInspection stops = adapter(client, gatewayMock()).inspectStops("BTCUSDT");

        assertThat(stops.hasStop()).isFalse();
        assertThat(stops.stopLoss()).isNull();
        assertThat(stops.takeProfit()).isNull();
    }

    // ---- listOpenOrders ----

    @Test
    void listOpenOrdersMapsExchangeRowsToAdapterOpenOrders() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("data", List.of(
                Map.of("orderId", "42", "clientOrderId", "cid-42", "side", "SELL", "type", "STOP_MARKET",
                        "origQty", "0.5", "price", "0", "stopPrice", "58000", "status", "NEW"))));

        List<AdapterOpenOrder> orders = adapter(client, gatewayMock()).listOpenOrders("BTCUSDT");

        assertThat(orders).hasSize(1);
        AdapterOpenOrder o = orders.get(0);
        assertThat(o.orderId()).isEqualTo("42");
        assertThat(o.clientOrderId()).isEqualTo("cid-42");
        assertThat(o.triggerPrice()).isEqualByComparingTo("58000");
        assertThat(o.status()).isEqualTo("NEW");
    }

    // ---- cancelReduceOnlyOrder ----

    @Test
    void cancelReduceOnlyOrderCancelsTheMatchingStopMarketOrder() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("data", List.of(
                Map.of("orderId", "sl-1", "type", "STOP_MARKET", "stopPrice", "58000"))));
        BinanceExecutionGateway gateway = gatewayMock();
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        adapter.cancelReduceOnlyOrder("BTCUSDT", true);

        verify(gateway).cancelOrder("BTCUSDT", "sl-1", null);
    }

    @Test
    void cancelReduceOnlyOrderIsNoOpWhenNothingMatches() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        when(client.getOpenOrders("BTCUSDT")).thenReturn(Map.of("data", List.of()));
        BinanceExecutionGateway gateway = gatewayMock();
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        adapter.cancelReduceOnlyOrder("BTCUSDT", true);

        verify(gateway, never()).cancelOrder(any(), any(), any());
    }

    // ---- placeReduceOnlyStop / placeReduceOnlyTakeProfit: closePosition=true, not explicit qty ----

    @Test
    void placeReduceOnlyStopPlacesAClosePositionStopMarketOrder() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        adapter.placeReduceOnlyStop("BTCUSDT", new BigDecimal("0.5"), new BigDecimal("58000"));

        ArgumentCaptor<BinancePlaceOrderRequest> captor = ArgumentCaptor.forClass(BinancePlaceOrderRequest.class);
        verify(gateway).placeOrder(captor.capture(), Mockito.eq(OrderIntent.CLOSE_REDUCE));
        BinancePlaceOrderRequest req = captor.getValue();
        assertThat(req.closePosition()).isTrue();
        assertThat(req.type().name()).isEqualTo("STOP_MARKET");
        assertThat(req.stopPrice()).isEqualTo("58000");
        assertThat(req.side().name()).isEqualTo("SELL");
    }

    @Test
    void placeReduceOnlyTakeProfitPlacesAClosePositionTakeProfitMarketOrder() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        adapter.placeReduceOnlyTakeProfit("BTCUSDT", new BigDecimal("0.5"), new BigDecimal("65000"));

        ArgumentCaptor<BinancePlaceOrderRequest> captor = ArgumentCaptor.forClass(BinancePlaceOrderRequest.class);
        verify(gateway).placeOrder(captor.capture(), Mockito.eq(OrderIntent.CLOSE_REDUCE));
        assertThat(captor.getValue().type().name()).isEqualTo("TAKE_PROFIT_MARKET");
        assertThat(captor.getValue().closePosition()).isTrue();
    }

    // ---- getMarkPrice: must read the dedicated mark-price endpoint, not last-traded ----

    @Test
    void getMarkPriceReadsFromTheMarkPriceEndpoint() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        when(client.getMarkPrice("BTCUSDT")).thenReturn(Map.of("symbol", "BTCUSDT", "markPrice", "60100.5"));

        BigDecimal price = adapter(client, gatewayMock()).getMarkPrice("BTCUSDT");

        assertThat(price).isEqualByComparingTo("60100.5");
        verify(client, never()).getPrice(anyString());
    }

    // ---- closeAllPositions ----

    @Test
    void closeAllPositionsSweepsOrdersThenClosesEveryNonFlatPositionWithReduceOnlyMarket() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        when(gateway.getPositions(null)).thenReturn(Map.of("data", List.of(
                Map.of("symbol", "BTCUSDT", "positionAmt", "0.5"),
                Map.of("symbol", "ETHUSDT", "positionAmt", "0"), // flat, skipped
                Map.of("symbol", "SOLUSDT", "positionAmt", "-3"))));
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        adapter.closeAllPositions(null);

        ArgumentCaptor<BinancePlaceOrderRequest> captor = ArgumentCaptor.forClass(BinancePlaceOrderRequest.class);
        verify(gateway, Mockito.times(2)).placeOrder(captor.capture(), Mockito.eq(OrderIntent.CLOSE_REDUCE));
        assertThat(captor.getAllValues()).extracting(BinancePlaceOrderRequest::symbol)
                .containsExactlyInAnyOrder("BTCUSDT", "SOLUSDT");
        assertThat(captor.getAllValues()).allSatisfy(o -> assertThat(o.reduceOnly()).isTrue());
        // Long (positive amt) closes SELL; short (negative amt) closes BUY.
        assertThat(captor.getAllValues()).anySatisfy(o -> {
            if (o.symbol().equals("BTCUSDT")) {
                assertThat(o.side().name()).isEqualTo("SELL");
            }
        });
        assertThat(captor.getAllValues()).anySatisfy(o -> {
            if (o.symbol().equals("SOLUSDT")) {
                assertThat(o.side().name()).isEqualTo("BUY");
            }
        });
    }

    @Test
    void closeAllPositionsIsNoOpWhenEverythingIsFlat() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        when(gateway.getPositions(null)).thenReturn(Map.of("data", List.of(
                Map.of("symbol", "BTCUSDT", "positionAmt", "0"))));
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        adapter.closeAllPositions(null);

        verify(gateway, never()).placeOrder(any(), any());
    }

    // ---- toResult / order-status mapping ----

    @Test
    void getOrderStatusMapsExecutedQtyAndAvgPriceFromTheExchangeResponse() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        BinanceExecutionGateway gateway = gatewayMock();
        when(gateway.getOrder("BTCUSDT", null, "cid-9")).thenReturn(Map.of(
                "orderId", "9", "status", "FILLED", "executedQty", "0.5", "avgPrice", "60000"));
        BinanceExecutionAdapter adapter = adapter(client, gateway);

        var result = adapter.getOrderStatus("BTCUSDT", "cid-9");

        assertThat(result.state()).isEqualTo(AdapterOrderState.FILLED);
        assertThat(result.filledQty()).isEqualByComparingTo("0.5");
        assertThat(result.avgPrice()).isEqualByComparingTo("60000");
    }
}
