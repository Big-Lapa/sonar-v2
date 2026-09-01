package com.sonar.trading.exchange.bybit;

/**
 * Typed Bybit V5 {@code /v5/order/create} request (USDT-perpetual, {@code category=linear}).
 * Quantity/price/triggerPrice are decimal strings per platform convention.
 *
 * <p>{@code positionIdx=0} everywhere — the platform runs one-way mode, mirroring BingX's
 * {@code positionSide=BOTH} (grilled Q2: hedge mode out of scope).
 *
 * <p>{@code takeProfitPrice}/{@code stopLossPrice} attach TP/SL to the entry order itself (Bybit
 * supports this natively, same shape as BingX's attached OCO). {@code triggerPrice}/{@code
 * triggerBy}/{@code triggerDirection} are for a STANDALONE reduce-only conditional order placed
 * after a fill (StopGuard remediation, PositionOpsService move) — mutually exclusive with the
 * attached TP/SL fields. Bybit requires {@code triggerDirection} on every conditional order
 * (1 = trigger when price rises to triggerPrice, 2 = trigger when price falls to it); the caller
 * derives it from side/order-purpose, this record only carries the resolved value.
 *
 * <p>{@code reduceOnly} must be set on CLOSE/REDUCE legs — same one-way-mode invariant as BingX:
 * without it a concurrent duplicate close nets through zero into an opposite position.
 */
public record BybitPlaceOrderRequest(
        String symbol,
        BybitOrderSide side,
        BybitOrderType type,
        String qty,
        String price,
        BybitTimeInForce timeInForce,
        String clientOrderId,
        java.math.BigDecimal takeProfitPrice,
        java.math.BigDecimal stopLossPrice,
        BybitTriggerBy tpSlTriggerBy,
        String triggerPrice,
        BybitTriggerBy triggerBy,
        Integer triggerDirection,
        boolean reduceOnly,
        int positionIdx
) {

    /** Plain entry, no TP/SL, no trigger — market/limit open. */
    public BybitPlaceOrderRequest(String symbol, BybitOrderSide side, BybitOrderType type,
                                   String qty, String price, BybitTimeInForce timeInForce, String clientOrderId) {
        this(symbol, side, type, qty, price, timeInForce, clientOrderId,
                null, null, null, null, null, null, false, 0);
    }

    /** Entry with attached TP/SL (T23-equivalent OCO), reduceOnly explicit. */
    public BybitPlaceOrderRequest(String symbol, BybitOrderSide side, BybitOrderType type,
                                   String qty, String price, BybitTimeInForce timeInForce, String clientOrderId,
                                   java.math.BigDecimal takeProfitPrice, java.math.BigDecimal stopLossPrice,
                                   BybitTriggerBy tpSlTriggerBy, boolean reduceOnly) {
        this(symbol, side, type, qty, price, timeInForce, clientOrderId,
                takeProfitPrice, stopLossPrice, tpSlTriggerBy, null, null, null, reduceOnly, 0);
    }

    /** Standalone reduce-only conditional order (remediation stop / moved TP). */
    public static BybitPlaceOrderRequest standaloneTrigger(String symbol, BybitOrderSide side,
                                                             String qty, String triggerPrice,
                                                             BybitTriggerBy triggerBy, int triggerDirection,
                                                             String clientOrderId) {
        return new BybitPlaceOrderRequest(symbol, side, BybitOrderType.Market, qty, null, null, clientOrderId,
                null, null, null, triggerPrice, triggerBy, triggerDirection, true, 0);
    }
}
