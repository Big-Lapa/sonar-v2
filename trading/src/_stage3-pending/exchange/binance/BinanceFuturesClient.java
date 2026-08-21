package com.sonar.trading.exchange.binance;

import com.sonar.trading.exchange.spi.ExchangeRestClient;

import java.util.Map;

/**
 * Binance USDT-M Futures signed client surface used by execution.
 * Extracted as an interface so collaborators can be mocked in tests.
 * Implemented by {@link com.sonar.trading.exchange.binance.http.BinanceFuturesHttpService}.
 * The exchange-agnostic operations (cancel + account reads) come from {@link ExchangeRestClient}.
 */
public interface BinanceFuturesClient extends ExchangeRestClient {

    Map<String, Object> placeOrder(BinancePlaceOrderRequest request);

    /** Latest price via public {@code /fapi/v1/ticker/price} (unsigned). */
    Map<String, Object> getPrice(String symbol);

    /** Set the symbol's initial leverage ({@code POST /fapi/v1/leverage}) — per-strategy param (PR-D). */
    Map<String, Object> setLeverage(String symbol, int leverage);

    /** Cancel ALL open orders of a symbol ({@code DELETE /fapi/v1/allOpenOrders}) — уборка
     *  условных TP/SL ног перед новым входом и после close-all (зомби-стопы, ревью #88). */
    Map<String, Object> cancelAllOpenOrders(String symbol);
}
