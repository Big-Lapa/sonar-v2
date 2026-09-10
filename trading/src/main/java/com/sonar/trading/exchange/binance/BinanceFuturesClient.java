package com.sonar.trading.exchange.binance;

import com.sonar.trading.exchange.spi.ExchangeRestClient;

import java.util.Map;

/**
 * Binance USDT-M Futures signed client surface used by execution / reconciliation. Extracted as
 * an interface so collaborators can be mocked in tests, mirroring
 * {@link com.sonar.trading.exchange.bybit.BybitFuturesClient}.
 * Implemented by {@link com.sonar.trading.exchange.binance.http.BinanceFuturesHttpService}.
 * The exchange-agnostic operations (cancel + account reads) come from {@link ExchangeRestClient}.
 */
public interface BinanceFuturesClient extends ExchangeRestClient {

    Map<String, Object> placeOrder(BinancePlaceOrderRequest request);

    /** Latest traded price via public {@code /fapi/v1/ticker/price} (unsigned). */
    Map<String, Object> getPrice(String symbol);

    /**
     * Current mark price via public {@code /fapi/v1/premiumIndex} (unsigned) — the price
     * conditional orders actually trigger against when {@code workingType=MARK_PRICE}, which is
     * the default here (see {@code TradingProperties.TpSlProps}). Distinct from {@link #getPrice}
     * (last traded), which can diverge from mark price on a thin book.
     */
    Map<String, Object> getMarkPrice(String symbol);

    /** Set the symbol's initial leverage ({@code POST /fapi/v1/leverage}) — per-strategy param. */
    Map<String, Object> setLeverage(String symbol, int leverage);

    /** All open orders for a symbol, including untriggered conditional TP/SL orders. */
    Map<String, Object> getOpenOrders(String symbol);

    /**
     * Cancel ALL open orders of a symbol ({@code DELETE /fapi/v1/allOpenOrders}) — sweeps
     * leftover conditional TP/SL legs before a new entry and after close-all, since a triggered
     * TP otherwise leaves its sibling SL resting against whatever position opens next (zombie stop).
     */
    Map<String, Object> cancelAllOpenOrders(String symbol);

    /** Realized PnL + funding/fees ledger ({@code GET /fapi/v1/income}). */
    Map<String, Object> getIncome(String incomeType, Long startTime, Long endTime, Integer limit);
}
