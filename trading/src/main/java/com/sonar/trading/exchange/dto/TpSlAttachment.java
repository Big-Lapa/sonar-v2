package com.sonar.trading.exchange.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Optional TP/SL prices attached to an ENTRY order (T23). Null means not attached.
 *
 * <p>{@code tp1Price}/{@code tp1Quantity} — optional partial take-profit (volspike-pressure-v1,
 * parameter {@code partialTpEnabled}, off by default): a separate reduce-only take-profit for part of
 * the position, closer than the main TP. Null = no partial take-profit. Moving the stop to breakeven after
 * the partial fill ({@code beAfterPartial}) is NOT automated yet — enable partial TP deliberately.
 *
 * <p>{@code referencePrice} — the price the levels were computed from in {@code ExecutionEngine}: this is
 * the close of the last candle from {@code market.data}, i.e. the SPOT price for symbols with
 * {@code volume_source=SPOT}, and it is already up to a minute stale. The order itself goes to the perp.
 * {@code tickSize} — the symbol's price step from the same source. The pair is needed for {@link #rescaledTo(BigDecimal)}:
 * a venue that places conditional legs as SEPARATE orders after the fill (Binance) recomputes
 * the levels from the actual entry price, preserving exactly the percentages the strategy declared. BingX
 * attaches TP/SL to the entry order itself (attached OCO), i.e. it knows the levels only BEFORE the fill and
 * cannot apply the recomputation — there the levels stay at referencePrice.
 */
public record TpSlAttachment(BigDecimal takeProfitPrice, BigDecimal stopLossPrice,
                             BigDecimal tp1Price, BigDecimal tp1Quantity,
                             BigDecimal referencePrice, BigDecimal tickSize) {

    /** Common case: only the main TP/SL pair. */
    public TpSlAttachment(BigDecimal takeProfitPrice, BigDecimal stopLossPrice) {
        this(takeProfitPrice, stopLossPrice, null, null, null, null);
    }

    public TpSlAttachment(BigDecimal takeProfitPrice, BigDecimal stopLossPrice,
                          BigDecimal tp1Price, BigDecimal tp1Quantity) {
        this(takeProfitPrice, stopLossPrice, tp1Price, tp1Quantity, null, null);
    }

    public boolean hasPartial() {
        return tp1Price != null && tp1Quantity != null && tp1Quantity.signum() > 0;
    }

    /**
     * Levels recomputed from the ACTUAL entry price. All prices are multiplied by
     * {@code actualEntryPrice / referencePrice}, so the declared percentages are preserved exactly
     * (levels are built multiplicatively), including the partial leg. The {@code tp1Quantity} volume is not
     * touched — it does not depend on price.
     *
     * <p>Returns {@code this} if there is nothing to recompute from: no reference, no actual
     * price (the order has no fill yet), or it equals reference. This is the safe path — the levels simply
     * stay as they were, the entry has already happened and the position must get protection anyway.
     */
    public TpSlAttachment rescaledTo(BigDecimal actualEntryPrice) {
        if (referencePrice == null || referencePrice.signum() <= 0
                || actualEntryPrice == null || actualEntryPrice.signum() <= 0
                || actualEntryPrice.compareTo(referencePrice) == 0) {
            return this;
        }
        BigDecimal ratio = actualEntryPrice.divide(referencePrice, 12, RoundingMode.HALF_UP);
        return new TpSlAttachment(
                scaled(takeProfitPrice, ratio),
                scaled(stopLossPrice, ratio),
                scaled(tp1Price, ratio),
                tp1Quantity,
                actualEntryPrice,
                tickSize);
    }

    private BigDecimal scaled(BigDecimal price, BigDecimal ratio) {
        return price == null ? null : roundToTick(price.multiply(ratio));
    }

    /** Rounding to the symbol's price step; without a known step the price is left as is. */
    private BigDecimal roundToTick(BigDecimal price) {
        if (tickSize == null || tickSize.signum() <= 0) {
            return price;
        }
        if (tickSize.compareTo(BigDecimal.ONE) == 0) {
            return price.setScale(0, RoundingMode.HALF_UP);
        }
        return price.divide(tickSize, 0, RoundingMode.HALF_UP).multiply(tickSize);
    }
}
