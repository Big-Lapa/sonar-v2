package com.sonar.trading.exchange.bybit.http;

import com.sonar.trading.exchange.bybit.BybitOrderSide;
import com.sonar.trading.exchange.bybit.BybitOrderType;
import com.sonar.trading.exchange.bybit.BybitPlaceOrderRequest;
import com.sonar.trading.exchange.bybit.BybitTimeInForce;
import com.sonar.trading.exchange.bybit.BybitTriggerBy;
import com.sonar.trading.exchange.exception.ExchangeException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code buildPlaceOrderBody}/{@code requireOk} are package-private specifically so they can be
 * unit-tested without HTTP — same rationale as {@code BingxFuturesHttpService.buildPlaceOrderQuery}.
 */
class BybitFuturesHttpServiceTest {

    @Test
    void plainEntryOrderCarriesCategoryAndOneWayPositionIdx() {
        BybitPlaceOrderRequest req = new BybitPlaceOrderRequest("BTCUSDT", BybitOrderSide.Buy,
                BybitOrderType.Market, "0.01", null, null, "cid-1");

        Map<String, Object> body = BybitFuturesHttpService.buildPlaceOrderBody(req);

        assertThat(body).containsEntry("category", "linear");
        assertThat(body).containsEntry("symbol", "BTCUSDT");
        assertThat(body).containsEntry("side", "Buy");
        assertThat(body).containsEntry("orderType", "Market");
        assertThat(body).containsEntry("qty", "0.01");
        assertThat(body).containsEntry("orderLinkId", "cid-1");
        assertThat(body).containsEntry("positionIdx", 0);
        assertThat(body).doesNotContainKey("reduceOnly");
        assertThat(body).doesNotContainKey("price");
        assertThat(body).doesNotContainKey("timeInForce");
    }

    @Test
    void limitOrderCarriesPriceAndTimeInForce() {
        BybitPlaceOrderRequest req = new BybitPlaceOrderRequest("ETHUSDT", BybitOrderSide.Sell,
                BybitOrderType.Limit, "1", "2500.5", BybitTimeInForce.PostOnly, "cid-2");

        Map<String, Object> body = BybitFuturesHttpService.buildPlaceOrderBody(req);

        assertThat(body).containsEntry("price", "2500.5");
        assertThat(body).containsEntry("timeInForce", "PostOnly");
    }

    @Test
    void attachedTakeProfitStopLossCarryTriggerBy() {
        BybitPlaceOrderRequest req = new BybitPlaceOrderRequest("BTCUSDT", BybitOrderSide.Buy,
                BybitOrderType.Market, "0.01", null, null, "cid-3",
                new BigDecimal("70000"), new BigDecimal("60000"), BybitTriggerBy.MarkPrice, false);

        Map<String, Object> body = BybitFuturesHttpService.buildPlaceOrderBody(req);

        assertThat(body).containsEntry("takeProfit", "70000");
        assertThat(body).containsEntry("stopLoss", "60000");
        assertThat(body).containsEntry("tpTriggerBy", "MarkPrice");
        assertThat(body).containsEntry("slTriggerBy", "MarkPrice");
    }

    @Test
    void reduceOnlyEntryCarriesTheFlag() {
        BybitPlaceOrderRequest req = new BybitPlaceOrderRequest("BTCUSDT", BybitOrderSide.Sell,
                BybitOrderType.Market, "0.01", null, null, "cid-4",
                null, null, null, true);

        Map<String, Object> body = BybitFuturesHttpService.buildPlaceOrderBody(req);

        assertThat(body).containsEntry("reduceOnly", true);
    }

    @Test
    void standaloneTriggerOrderCarriesTriggerPriceByAndDirection() {
        BybitPlaceOrderRequest req = BybitPlaceOrderRequest.standaloneTrigger("BTCUSDT", BybitOrderSide.Sell,
                "0.01", "58000", BybitTriggerBy.MarkPrice, 2, "cid-5");

        Map<String, Object> body = BybitFuturesHttpService.buildPlaceOrderBody(req);

        assertThat(body).containsEntry("triggerPrice", "58000");
        assertThat(body).containsEntry("triggerBy", "MarkPrice");
        assertThat(body).containsEntry("triggerDirection", 2);
        assertThat(body).containsEntry("reduceOnly", true);
        assertThat(body).doesNotContainKey("takeProfit");
        assertThat(body).doesNotContainKey("stopLoss");
    }

    // ---- requireOk: Bybit's "200 + retCode != 0" business-error gotcha ----

    @Test
    void requireOkPassesThroughSuccessResponse() {
        Map<String, Object> resp = Map.of("retCode", 0, "retMsg", "OK", "result", Map.of());

        Map<String, Object> result = BybitFuturesHttpService.requireOk(resp);

        assertThat(result).isSameAs(resp);
    }

    @Test
    void requireOkThrowsOnNonZeroRetCode() {
        Map<String, Object> resp = Map.of("retCode", 110007, "retMsg", "insufficient balance");

        assertThatThrownBy(() -> BybitFuturesHttpService.requireOk(resp))
                .isInstanceOf(ExchangeException.class)
                .satisfies(e -> {
                    ExchangeException ee = (ExchangeException) e;
                    assertThat(ee.getError().code()).isEqualTo("110007");
                    assertThat(ee.getError().message()).isEqualTo("insufficient balance");
                });
    }

    @Test
    void requireOkTreatsMissingRetCodeAsOk() {
        Map<String, Object> resp = Map.of("some", "field");

        assertThat(BybitFuturesHttpService.requireOk(resp)).isSameAs(resp);
    }
}
