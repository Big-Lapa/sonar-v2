package com.sonar.trading.exchange.bybit;

/** Bybit V5 trigger price source for conditional/TP/SL orders — analog of BingxWorkingType. */
public enum BybitTriggerBy {
    LastPrice, MarkPrice, IndexPrice
}
