package com.sonar.trading.exchange.spi;

import com.sonar.trading.exchange.dto.AdapterOpenOrder;
import com.sonar.trading.exchange.dto.AdapterOrderRequest;
import com.sonar.trading.exchange.dto.AdapterOrderResult;
import com.sonar.trading.exchange.dto.StopInspection;

import java.math.BigDecimal;
import java.util.List;

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

    /** All resting orders for the symbol (manual panel's cancel-order screen) — every type, not
     *  just reduce-only conditionals ({@link #hasOpenStopOrder}'s narrower concern). */
    List<AdapterOpenOrder> listOpenOrders(String symbol);

    /** Cancel by the exchange's own order id — for an order this system may not have created
     *  itself (e.g. left over from hand-editing on the exchange), where no clientOrderId exists
     *  to cancel by. */
    void cancelOrderById(String symbol, String orderId);

    /** Force-close all positions for the symbol (kill-switch / FLIP support, ADR-0018/0019). */
    void closeAllPositions(String symbol);

    /** True if a reduce-only conditional stop order is currently open for the symbol. */
    boolean hasOpenStopOrder(String symbol);

    /**
     * The venue's own record of this symbol's protective stop/take-profit — presence AND levels,
     * read however this venue actually exposes them (see {@link StopInspection}'s javadoc for why
     * that isn't the same mechanism across venues). Throws on a failed venue call; the caller
     * ({@code StopGuard}) is responsible for treating "could not ask" as unknown, never as
     * "unprotected".
     */
    StopInspection inspectStops(String symbol);

    /**
     * Cancel the existing open reduce-only stop ({@code stopType=true}) or take-profit
     * ({@code stopType=false}) conditional order for the symbol, if any. No-op if none is open —
     * callers that want "replace" call this first, then {@link #placeReduceOnlyStop} /
     * {@link #placeReduceOnlyTakeProfit}.
     *
     * <p>How much of the replace this actually performs is venue-dependent. Where protection is a
     * separate order (BingX), this is the half that removes the old one and the position is briefly
     * unprotected between the two calls. Where it is position state (Bybit), the place call
     * overwrites it atomically and this only sweeps up stray conditional orders — so callers must
     * treat the pair as "replace", never assume the cancel alone left the position unprotected.
     */
    void cancelReduceOnlyOrder(String symbol, boolean stopType);

    /** Place a standalone reduce-only conditional stop order (remediation / move). */
    void placeReduceOnlyStop(String symbol, BigDecimal qty, BigDecimal stopPrice);

    /** Place a standalone reduce-only conditional take-profit order (move). */
    void placeReduceOnlyTakeProfit(String symbol, BigDecimal qty, BigDecimal price);

    /** Current mark price for the symbol (drift gate on confirm). */
    BigDecimal getMarkPrice(String symbol);

    String adapterName();
}
