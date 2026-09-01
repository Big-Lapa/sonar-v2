package com.sonar.trading.exchange.bybit;

import com.sonar.trading.exchange.model.AdapterOrderState;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bybit V5 &lt;-&gt; platform mapping. Pure functions: symbol passthrough (Bybit's linear-perp
 * symbol IS the core form, e.g. {@code BTCUSDT} — unlike BingX's {@code BTC-USDT}, no dash to
 * strip/add), nested JSON field extraction (V5 wraps payloads under {@code result}, often with an
 * inner {@code list} array), and order-state mapping into {@link AdapterOrderState}.
 */
public final class BybitOrderMapper {

    private BybitOrderMapper() {}

    // ---- Symbols (identity — kept for interface symmetry with BingxOrderMapper / future venues) ----

    public static String toBybitSymbol(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("symbol is null");
        }
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    public static String toCoreSymbol(String bybitSymbol) {
        return bybitSymbol == null ? null : bybitSymbol.toUpperCase(Locale.ROOT);
    }

    // ---- Order state ----

    /** Maps a Bybit V5 {@code orderStatus} string into {@link AdapterOrderState}. Null/unknown -> NEW. */
    public static AdapterOrderState mapState(String status) {
        if (status == null) {
            return AdapterOrderState.NEW;
        }
        String v = status.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "CREATED", "NEW", "UNTRIGGERED", "TRIGGERED" -> AdapterOrderState.NEW;
            case "PARTIALLYFILLED" -> AdapterOrderState.PARTIALLY_FILLED;
            case "FILLED" -> AdapterOrderState.FILLED;
            case "CANCELLED", "CANCELED", "PARTIALLYFILLEDCANCELED", "DEACTIVATED" -> AdapterOrderState.CANCELED;
            case "REJECTED" -> AdapterOrderState.REJECTED;
            default -> AdapterOrderState.NEW;
        };
    }

    // ---- Field extraction (V5 wraps payloads under result, often result.list[0]) ----

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
                cur = descend(cur, part);
                if (cur == null) {
                    return null;
                }
            }
            return cur instanceof String s ? s : String.valueOf(cur);
        }
        if (map.containsKey(key)) {
            Object v = map.get(key);
            return v == null ? null : String.valueOf(v);
        }
        // Fall back to the "result" envelope, and its "list" array's first row.
        Object result = map.get("result");
        if (result instanceof Map<?, ?> rm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = (Map<String, Object>) rm;
            if (resultMap.containsKey(key)) {
                Object v = resultMap.get(key);
                return v == null ? null : String.valueOf(v);
            }
            Object list = resultMap.get("list");
            if (list instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> row) {
                Object v = row.get(key);
                return v == null ? null : String.valueOf(v);
            }
        }
        return null;
    }

    private static Object descend(Object cur, String field) {
        // "list.0" style index isn't used here — dotted paths address object fields; list rows are
        // the last hop and addressed via extractString's default (first row) semantics elsewhere.
        if (cur instanceof Map<?, ?> m) {
            if (m.containsKey(field)) {
                return m.get(field);
            }
            return null;
        }
        return null;
    }

    /**
     * Rows out of a Bybit V5 {@code result.list} array (positions, open orders, balances, ...).
     * Tolerant: missing/malformed shapes yield an empty list rather than throwing.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> resultList(Map<String, Object> resp) {
        if (resp == null) {
            return List.of();
        }
        Object result = resp.get("result");
        if (!(result instanceof Map<?, ?> rm)) {
            return List.of();
        }
        Object list = rm.get("list");
        if (!(list instanceof List<?> l)) {
            return List.of();
        }
        return (List<Map<String, Object>>) (List<?>) l.stream().filter(o -> o instanceof Map<?, ?>).toList();
    }
}
