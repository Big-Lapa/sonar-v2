package com.sonar.trading.exchange.binance;

/**
 * Binance position side. The platform runs One-Way Mode, so every order carries {@code BOTH}
 * (mirrors BingX's {@code positionSide=BOTH} / Bybit's {@code positionIdx=0}). LONG/SHORT exist
 * only for hedge-mode accounts, which this platform does not use.
 */
public enum BinancePositionSide {
    BOTH, LONG, SHORT
}
