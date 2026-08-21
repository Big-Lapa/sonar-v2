package com.sonar.trading.exchange.bingx;

import com.sonar.trading.exchange.model.AdapterOrderState;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * BingX Swap V2 ↔ platform mapping (T13) — ported from the CryptoSonar {@code BingxFuturesTrade}.
 *
 * <p>Pure functions: symbol conversion, nested JSON field extraction (BingX wraps payloads under
 * {@code data}/{@code order}), and order-state mapping into {@link AdapterOrderState}.
 */
public final class BingxOrderMapper {

    private BingxOrderMapper() {}

    // ---- Symbols ----

    /** Core symbol ({@code BTCUSDT}) → BingX symbol ({@code BTC-USDT}). */
    public static String toBingxSymbol(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("symbol is null");
        }
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        if (s.contains("-")) {
            return s;
        }
        for (String quote : new String[]{"USDT", "USDC", "BUSD"}) {
            if (s.endsWith(quote) && s.length() > quote.length()) {
                return s.substring(0, s.length() - quote.length()) + '-' + quote;
            }
        }
        return s; // fallback
    }

    /** BingX symbol ({@code BTC-USDT}) → core symbol ({@code BTCUSDT}). */
    public static String toCoreSymbol(String bingxSymbol) {
        return bingxSymbol == null ? null : bingxSymbol.replace("-", "");
    }

    // ---- Order state ----

    /** Maps a BingX order status string into {@link AdapterOrderState}. Null/unknown → NEW. */
    public static AdapterOrderState mapState(String status) {
        if (status == null) {
            return AdapterOrderState.NEW;
        }
        String v = status.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "NEW", "SUBMITTED", "ACCEPTED", "PENDING" -> AdapterOrderState.NEW;
            case "PARTIALLY_FILLED", "PARTIALLYFILLED", "PARTIAL" -> AdapterOrderState.PARTIALLY_FILLED;
            case "FILLED", "EXECUTED" -> AdapterOrderState.FILLED;
            case "CANCELED", "CANCELLED" -> AdapterOrderState.CANCELED;
            case "REJECTED", "EXPIRED" -> AdapterOrderState.REJECTED;
            default -> AdapterOrderState.NEW;
        };
    }

    // ---- Field extraction (BingX nests under data / order) ----

    /** First non-blank string for any of {@code keys}, supporting dotted nested paths. */
    public static String extractString(Map<String, Object> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            String v = extractOne(map, key);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** First parseable decimal for any of {@code keys}, supporting dotted nested paths. */
    public static BigDecimal extractDecimal(Map<String, Object> map, String... keys) {
        String s = extractString(map, keys);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractOne(Map<String, Object> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        // Dotted nested path, e.g. "data.order.orderId"
        if (key.contains(".")) {
            Object cur = map;
            for (String part : key.split("\\.")) {
                if (!(cur instanceof Map<?, ?> cm) || !cm.containsKey(part)) {
                    return null;
                }
                cur = cm.get(part);
            }
            return cur == null ? null : String.valueOf(cur);
        }
        if (map.containsKey(key)) {
            Object v = map.get(key);
            return v == null ? null : String.valueOf(v);
        }
        // Fall back to a one-level "data" envelope
        Object data = map.get("data");
        if (data instanceof Map<?, ?> dm && dm.containsKey(key)) {
            Object v = dm.get(key);
            return v == null ? null : String.valueOf(v);
        }
        return null;
    }
}
