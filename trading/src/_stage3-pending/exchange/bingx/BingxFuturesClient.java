package com.sonar.trading.exchange.bingx;

import com.sonar.trading.exchange.spi.ExchangeRestClient;

import java.util.Map;

/**
 * BingX Swap V2 signed client surface used by execution / reconciliation.
 * Extracted as an interface so collaborators can be mocked in tests.
 * Implemented by {@link com.sonar.trading.exchange.bingx.http.BingxFuturesHttpService}.
 * The exchange-agnostic operations (cancel + account reads) come from {@link ExchangeRestClient}.
 */
public interface BingxFuturesClient extends ExchangeRestClient {

    Map<String, Object> placeOrder(BingxPlaceOrderRequest request);

    Map<String, Object> closeAllPositions(String symbol);

    /** All open orders for a symbol, including pending TP/SL conditional orders. */
    Map<String, Object> getOpenOrders(String symbol);

    Map<String, Object> getPrice(String symbol, String type);

    /**
     * Account income history (Swap V2 {@code /user/income}). {@code incomeType} filters the ledger
     * (e.g. {@code FUNDING_FEE}); null = all types. {@code startTime}/{@code endTime} are epoch ms
     * (null = exchange default window); {@code limit} caps rows (null = default). Read path — used
     * by realized-funding telemetry, never on the order hot-path.
     */
    Map<String, Object> getIncome(String incomeType, Long startTime, Long endTime, Integer limit);
}
