package com.sonar.trading.exchange.bingx;

/**
 * Typed BingX Swap V2 place-order request (T12/T23). Quantity and price are decimal strings
 * per platform money/quantity convention; all other variable fields use typed enums.
 *
 * <p>{@code positionSide} may be null — defaults to {@code BOTH} in
 * {@link com.sonar.trading.exchange.bingx.http.BingxFuturesHttpService}
 * (the platform is one-way; see BUG-1/T22). {@code price}/{@code timeInForce} only for limit orders.
 *
 * <p>{@code takeProfit}/{@code stopLoss} carry attached OCO orders (T23): placed in the same
 * BingX request and auto-cancelled when the other side triggers.
 *
 * <p>{@code stopPrice} (decimal string) is used for standalone conditional orders (TAKE_PROFIT /
 * STOP_MARKET placed separately after a fill). Mutually exclusive with attached takeProfit/stopLoss.
 *
 * <p>{@code reduceOnly} must be set on CLOSE/REDUCE legs (one-way mode): without it a concurrent
 * duplicate close nets through zero into an opposite position instead of being rejected.
 */
public record BingxPlaceOrderRequest(
        String symbol,
        BingxOrderSide side,
        BingxPositionSide positionSide,
        BingxOrderType type,
        String quantity,
        String price,
        BingxTimeInForce timeInForce,
        String clientOrderId,
        BingxTpSlOrder takeProfit,
        BingxTpSlOrder stopLoss,
        String stopPrice,
        boolean reduceOnly
) {

    /** Without TP/SL, stopPrice or reduceOnly. */
    public BingxPlaceOrderRequest(String symbol, BingxOrderSide side, BingxPositionSide positionSide,
                                   BingxOrderType type, String quantity, String price,
                                   BingxTimeInForce timeInForce, String clientOrderId) {
        this(symbol, side, positionSide, type, quantity, price, timeInForce, clientOrderId,
                null, null, null, false);
    }

    /** With attached TP/SL and reduceOnly flag, without standalone stopPrice. */
    public BingxPlaceOrderRequest(String symbol, BingxOrderSide side, BingxPositionSide positionSide,
                                   BingxOrderType type, String quantity, String price,
                                   BingxTimeInForce timeInForce, String clientOrderId,
                                   BingxTpSlOrder takeProfit, BingxTpSlOrder stopLoss,
                                   boolean reduceOnly) {
        this(symbol, side, positionSide, type, quantity, price, timeInForce, clientOrderId,
                takeProfit, stopLoss, null, reduceOnly);
    }
}
