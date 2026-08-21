package com.sonar.trading.exchange.binance;

/**
 * Binance position side. The platform runs One-Way Mode, so every order carries {@code BOTH}
 * (mirrors BingX BUG-1/T22 constraint). LONG/SHORT exist only for hedge accounts (unsupported).
 */
public enum BinancePositionSide {
    BOTH, LONG, SHORT
}
