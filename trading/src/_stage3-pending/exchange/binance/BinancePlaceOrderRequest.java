package com.sonar.trading.exchange.binance;

/**
 * Typed Binance USDT-M Futures place-order request. Quantity/price are decimal strings per the
 * platform money/quantity convention; the rest are typed enums.
 *
 * <p>{@code positionSide} may be null — defaults to {@code BOTH} in
 * {@link com.sonar.trading.exchange.binance.http.BinanceFuturesHttpService}
 * (One-Way Mode). {@code price}/{@code timeInForce} only for LIMIT orders.
 *
 * <p>{@code reduceOnly} is used for close legs (One-Way Mode reduce-only MARKET). {@code stopPrice}
 * is reserved for standalone STOP_MARKET/TAKE_PROFIT_MARKET conditional orders (TP/SL, deferred).
 *
 * <p>{@code newClientOrderId} must match Binance regex {@code ^[.A-Za-z0-9:/_-]{1,36}$} — callers
 * clamp to 36 chars via {@link BinanceOrderMapper#clampClientOrderId(String)}.
 */
public record BinancePlaceOrderRequest(
        String symbol,
        BinanceOrderSide side,
        BinancePositionSide positionSide,
        BinanceOrderType type,
        String quantity,
        String price,
        BinanceTimeInForce timeInForce,
        String newClientOrderId,
        boolean reduceOnly,
        String stopPrice,
        /** {@code closePosition=true}: the conditional order closes the WHOLE position (quantity/reduceOnly
         *  are not sent); Binance itself removes such a trigger when the position goes to zero — the standard
         *  OCO equivalent for full-size TP/SL legs (review #88). */
        boolean closePosition,
        /** workingType of the conditional order: MARK_PRICE | CONTRACT_PRICE; null = exchange default. */
        String workingType
) {

    /** Common case: no reduceOnly, no stopPrice. */
    public BinancePlaceOrderRequest(String symbol, BinanceOrderSide side, BinancePositionSide positionSide,
                                    BinanceOrderType type, String quantity, String price,
                                    BinanceTimeInForce timeInForce, String newClientOrderId) {
        this(symbol, side, positionSide, type, quantity, price, timeInForce, newClientOrderId,
            false, null, false, null);
    }

    /** Pre-PR-D signature (reduceOnly+stopPrice): without closePosition/workingType. */
    public BinancePlaceOrderRequest(String symbol, BinanceOrderSide side, BinancePositionSide positionSide,
                                    BinanceOrderType type, String quantity, String price,
                                    BinanceTimeInForce timeInForce, String newClientOrderId,
                                    boolean reduceOnly, String stopPrice) {
        this(symbol, side, positionSide, type, quantity, price, timeInForce, newClientOrderId,
            reduceOnly, stopPrice, false, null);
    }
}
