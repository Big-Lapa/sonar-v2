package com.sonar.trading.exchange.spi;

import com.sonar.trading.exchange.dto.AdapterOrderRequest;
import com.sonar.trading.exchange.dto.AdapterOrderResult;

import java.math.BigDecimal;

/**
 * Venue-agnostic execution surface. Beyond entry/close, this also carries the reduce-only
 * stop/take-profit primitives {@code StopGuard} and {@code PositionOpsService} need — added so
 * those two stay ONE venue-agnostic implementation each instead of forking per exchange (grilled
 * Q5: extend the SPI rather than duplicate the remediation/move logic per venue).
 */
public interface ExecutionAdapter {

    AdapterOrderResult placeOrder(AdapterOrderRequest request);

    AdapterOrderResult getOrderStatus(String symbol, String clientOrderId);

    void cancelOrder(String symbol, String clientOrderId);

    /** Force-close all positions for the symbol (kill-switch / FLIP support, ADR-0018/0019). */
    void closeAllPositions(String symbol);

    /** True if a reduce-only conditional stop order is currently open for the symbol. */
    boolean hasOpenStopOrder(String symbol);

    /**
     * Cancel the existing open reduce-only stop ({@code stopType=true}) or take-profit
     * ({@code stopType=false}) conditional order for the symbol, if any. No-op if none is open —
     * callers that want "replace" call this first, then {@link #placeReduceOnlyStop} /
     * {@link #placeReduceOnlyTakeProfit}.
     */
    void cancelReduceOnlyOrder(String symbol, boolean stopType);

    /** Place a standalone reduce-only conditional stop order (remediation / move). */
    void placeReduceOnlyStop(String symbol, BigDecimal qty, BigDecimal stopPrice);

    /** Place a standalone reduce-only conditional take-profit order (move). */
    void placeReduceOnlyTakeProfit(String symbol, BigDecimal qty, BigDecimal price);

    /** Current mark price for the symbol (drift gate on confirm — 4.6 step 1). */
    BigDecimal getMarkPrice(String symbol);

    String adapterName();
}
