package com.sonar.trading.exchange.binance;

/**
 * Binance Futures time-in-force. {@code GTX} is Binance's post-only ("Good Till Crossing" —
 * rejected if it would take liquidity), the equivalent of BingX {@code PostOnly}.
 */
public enum BinanceTimeInForce {
    GTC, GTX
}
