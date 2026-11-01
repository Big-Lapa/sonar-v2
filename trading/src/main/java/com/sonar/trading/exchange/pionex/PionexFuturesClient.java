package com.sonar.trading.exchange.pionex;

import java.util.Map;

/**
 * Pionex Futures ({@code /uapi/v1}) signed client surface — read-only account access only for
 * this iteration (balance + positions, used by {@code PionexAccountReader}). Order placement
 * ({@code /uapi/v1/trade/order}) is deliberately not exposed here yet; see the v1 reference
 * ({@code sonar-platform/services/slippager/pionex/PionexFuturesClient.java}) when that lands.
 *
 * <p>Extracted as an interface — mirrors {@code BingxFuturesClient} — so it can be mocked in
 * tests. Implemented by {@link com.sonar.trading.exchange.pionex.http.PionexFuturesHttpService}.
 */
public interface PionexFuturesClient {

    /** Futures account balances, raw response ({@code data.balances[]} per the v1 reference). */
    Map<String, Object> getBalances();

    /** Current positions ({@code data.positions[]}), optionally scoped to a Pionex-form symbol
     *  (e.g. {@code BTC_USDT_PERP}); null/blank returns all. */
    Map<String, Object> getPositions(String symbol);

    /** Public spot book ticker ({@code GET /api/v1/market/bookTickers}, unsigned) for a Pionex-form
     *  pair such as {@code BTC_USDT}. Returned raw, envelope NOT checked: a {@code result:false}
     *  answer ({@code MARKET_INVALID_SYMBOL} = no such pair) is for the caller to classify. */
    Map<String, Object> getBookTicker(String symbol);
}
