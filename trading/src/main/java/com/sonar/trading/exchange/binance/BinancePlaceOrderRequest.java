package com.sonar.trading.exchange.binance;

import java.math.BigDecimal;

/**
 * Typed Binance USDT-M Futures place-order request. Quantity/price/stopPrice are decimal strings
 * per the platform's money/quantity convention.
 *
 * <p>{@code positionSide} may be null — defaults to {@code BOTH} in
 * {@link com.sonar.trading.exchange.binance.http.BinanceFuturesHttpService} (One-Way Mode).
 * {@code price}/{@code timeInForce} only apply to LIMIT orders.
 *
 * <p>Binance has no attach-to-entry TP/SL (unlike BingX/Bybit): protection is always a standalone
 * conditional order (STOP_MARKET / TAKE_PROFIT_MARKET). {@code closePosition=true} makes such an
 * order close the ENTIRE position when triggered — no quantity/reduceOnly needed, and Binance
 * itself cancels the sibling leg once the position is flat, giving an OCO-equivalent without a
 * native OCO endpoint. {@code workingType} selects the trigger price source
 * (MARK_PRICE/CONTRACT_PRICE); null uses the exchange default.
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
        boolean closePosition,
        String workingType
) {

    /** Plain entry/close, no conditional fields — market/limit open or reduce-only close. */
    public BinancePlaceOrderRequest(String symbol, BinanceOrderSide side, BinancePositionSide positionSide,
                                    BinanceOrderType type, String quantity, String price,
                                    BinanceTimeInForce timeInForce, String newClientOrderId, boolean reduceOnly) {
        this(symbol, side, positionSide, type, quantity, price, timeInForce, newClientOrderId,
                reduceOnly, null, false, null);
    }
}
