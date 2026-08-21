package com.sonar.trading.exchange.spi;

import com.sonar.trading.exchange.dto.AdapterOrderRequest;
import com.sonar.trading.exchange.dto.AdapterOrderResult;

public interface ExecutionAdapter {

    AdapterOrderResult placeOrder(AdapterOrderRequest request);

    AdapterOrderResult getOrderStatus(String symbol, String clientOrderId);

    void cancelOrder(String symbol, String clientOrderId);

    /** Force-close all positions for the symbol (kill-switch / FLIP support, ADR-0018/0019). */
    void closeAllPositions(String symbol);

    String adapterName();
}
