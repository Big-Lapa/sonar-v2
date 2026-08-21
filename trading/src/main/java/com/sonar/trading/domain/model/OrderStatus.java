package com.sonar.trading.domain.model;

public enum OrderStatus {
    PENDING,
    SUBMITTED,
    ACCEPTED,
    PARTIALLY_FILLED,
    FILLED,
    REJECTED,
    CANCELLED,
    FAILED
}
