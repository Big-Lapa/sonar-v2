package com.sonar.trading.exchange.binance;

import com.sonar.trading.exchange.model.AdapterOrderState;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;

/**
 * Binance USDT-M Futures ↔ platform mapping (mirror of {@code BingxOrderMapper}).
 *
 * <p>Pure functions: symbol conversion (identity — Binance uses {@code BTCUSDT}, same as the core
 * symbol), clientOrderId clamping (Binance limit is 36 chars), nested JSON field extraction, and
 * order-state mapping into {@link AdapterOrderState}.
 */
public final class BinanceOrderMapper {

    /** Binance {@code newClientOrderId} max length (regex {@code ^[.A-Za-z0-9:/_-]{1,36}$}). */
    public static final int MAX_CLIENT_ORDER_ID = 36;

    private BinanceOrderMapper() {}

    // ---- Symbols ----

    /** Core symbol ({@code BTCUSDT}) → Binance symbol. Binance uses no separator, so this is identity. */
    public static String toBinanceSymbol(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("symbol is null");
        }
        return symbol.trim().toUpperCase(Locale.ROOT).replace("-", "");
    }

    /** Binance symbol → core symbol (identity; both are dash-free upper-case). */
    public static String toCoreSymbol(String binanceSymbol) {
        return binanceSymbol == null ? null : binanceSymbol.replace("-", "");
    }

    /**
     * Clamp a clientOrderId to Binance's 36-char limit. Keeps the trailing chars (the unique command
     * suffix) rather than the exchange prefix, so the id stays unique. Returns null unchanged.
     *
     * <p>TODO(multi-account Phase 3): the platform's {@code accountId-commandId} scheme can exceed 36
     * chars; adopt a compact id scheme there instead of relying on this clamp.
     */
    public static String clampClientOrderId(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.length() <= MAX_CLIENT_ORDER_ID) {
            return clientOrderId;
        }
        return clientOrderId.substring(clientOrderId.length() - MAX_CLIENT_ORDER_ID);
    }

    // ---- Order state ----

    /** Maps a Binance order status string into {@link AdapterOrderState}. Null/unknown → NEW. */
    public static AdapterOrderState mapState(String status) {
        if (status == null) {
            return AdapterOrderState.NEW;
        }
        String v = status.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "NEW", "PENDING_NEW", "ACCEPTED" -> AdapterOrderState.NEW;
            case "PARTIALLY_FILLED" -> AdapterOrderState.PARTIALLY_FILLED;
            case "FILLED" -> AdapterOrderState.FILLED;
            case "CANCELED", "CANCELLED", "PENDING_CANCEL" -> AdapterOrderState.CANCELED;
            case "REJECTED", "EXPIRED", "EXPIRED_IN_MATCH" -> AdapterOrderState.REJECTED;
            default -> AdapterOrderState.NEW;
        };
    }

    // ---- Field extraction ----

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
        return null;
    }
}
