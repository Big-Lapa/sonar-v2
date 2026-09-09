package com.sonar.trading.exchange.dto;

import java.math.BigDecimal;

/**
 * What the venue itself reports about a symbol's protective stop/take-profit, as read by the
 * adapter for that venue. Deliberately built per-adapter, not centrally: BingX/Bybit's mechanics
 * are genuinely different (BingX — separate reduce-only conditional orders; Bybit — a
 * position-level field that never appears as an order until it triggers), and a venue-agnostic
 * reader that inspects raw order rows by {@code type} string silently misreads whichever venue it
 * wasn't written against (that happened here: Bybit's SL lives on the position, and its
 * conditional-order {@code type} field is split across {@code orderType}/{@code stopOrderType} in
 * a way BingX-shaped classification never matches).
 */
public record StopInspection(boolean hasStop, BigDecimal stopLoss, BigDecimal takeProfit) {

    public static final StopInspection NONE = new StopInspection(false, null, null);
}
