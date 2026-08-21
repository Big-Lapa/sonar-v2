package com.sonar.trading.exchange.model;

public enum AdapterOrderState {
    NEW,
    PARTIALLY_FILLED,
    FILLED,
    CANCELED,
    REJECTED,
    FAILED
}
