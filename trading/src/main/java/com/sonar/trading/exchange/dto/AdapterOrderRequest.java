package com.sonar.trading.exchange.dto;

import com.sonar.trading.exchange.model.AdapterOrderSide;
import com.sonar.trading.exchange.model.AdapterOrderType;
import com.sonar.trading.exchange.model.OrderIntent;

import java.math.BigDecimal;

public record AdapterOrderRequest(
        String symbol,
        AdapterOrderSide side,
        AdapterOrderType type,
        BigDecimal quantity,
        BigDecimal price,
        String clientOrderId,
        OrderIntent intent,
        String timeInForce,
        TpSlAttachment tpSl,
        /** Target leverage of the ENTRY order (per-strategy parameter); null = leave the exchange leverage untouched. */
        Integer leverage
) {

    /** Defaults intent=ENTRY, timeInForce=null, tpSl=null, leverage=null. */
    public AdapterOrderRequest(String symbol, AdapterOrderSide side, AdapterOrderType type,
                               BigDecimal quantity, BigDecimal price, String clientOrderId) {
        this(symbol, side, type, quantity, price, clientOrderId, OrderIntent.ENTRY, null, null, null);
    }

    /** Defaults timeInForce=null, tpSl=null, leverage=null. */
    public AdapterOrderRequest(String symbol, AdapterOrderSide side, AdapterOrderType type,
                               BigDecimal quantity, BigDecimal price, String clientOrderId,
                               OrderIntent intent) {
        this(symbol, side, type, quantity, price, clientOrderId, intent, null, null, null);
    }

    /** Defaults tpSl=null, leverage=null. */
    public AdapterOrderRequest(String symbol, AdapterOrderSide side, AdapterOrderType type,
                               BigDecimal quantity, BigDecimal price, String clientOrderId,
                               OrderIntent intent, String timeInForce) {
        this(symbol, side, type, quantity, price, clientOrderId, intent, timeInForce, null, null);
    }

    /** Defaults leverage=null (pre-PR-D signature). */
    public AdapterOrderRequest(String symbol, AdapterOrderSide side, AdapterOrderType type,
                               BigDecimal quantity, BigDecimal price, String clientOrderId,
                               OrderIntent intent, String timeInForce, TpSlAttachment tpSl) {
        this(symbol, side, type, quantity, price, clientOrderId, intent, timeInForce, tpSl, null);
    }
}
