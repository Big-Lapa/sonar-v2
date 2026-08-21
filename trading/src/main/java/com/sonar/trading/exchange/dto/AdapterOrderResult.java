package com.sonar.trading.exchange.dto;

import com.sonar.trading.exchange.model.AdapterOrderState;

import java.math.BigDecimal;

public record AdapterOrderResult(
        String exchangeOrderId,
        String clientOrderId,
        String symbol,
        AdapterOrderState state,
        BigDecimal filledQty,
        BigDecimal avgPrice,
        BigDecimal fee,
        long updatedAt
) {}
