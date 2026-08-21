package com.sonar.trading.exchange.binance;

/**
 * Binance USDT-M Futures order types. Core+smoke uses MARKET/LIMIT; the *_MARKET conditional
 * types are reserved for TP/SL (deferred — placed as separate reduceOnly/closePosition orders,
 * unlike BingX's attached OCO).
 */
public enum BinanceOrderType {
    MARKET, LIMIT, STOP_MARKET, TAKE_PROFIT_MARKET
}
