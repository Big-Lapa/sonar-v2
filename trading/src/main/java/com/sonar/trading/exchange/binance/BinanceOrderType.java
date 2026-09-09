package com.sonar.trading.exchange.binance;

/**
 * Binance USDT-M Futures order types used by this platform. MARKET/LIMIT are entries and
 * reduce-only closes; STOP_MARKET/TAKE_PROFIT_MARKET are the standalone conditional exits this
 * venue uses in place of BingX/Bybit's attached OCO (Binance has no attach-to-entry TP/SL).
 */
public enum BinanceOrderType {
    MARKET, LIMIT, STOP_MARKET, TAKE_PROFIT_MARKET
}
