package com.sonar.trading.exchange.binance;

import com.sonar.trading.exchange.model.AdapterOrderState;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Binance USDT-M Futures &lt;-&gt; platform mapping (mirrors {@code BybitOrderMapper} /
 * {@code BingxOrderMapper}).
 *
 * <p>Pure functions: symbol conversion (identity — Binance uses {@code BTCUSDT}, same as the core
 * symbol), clientOrderId clamping (Binance limit is 36 chars), nested JSON field extraction, and
 * order-state mapping into {@link AdapterOrderState}. Array responses
 * ({@code /positionRisk}, {@code /balance}, {@code /openOrders}) are wrapped under a top-level
 * {@code "data"} key by {@code BinanceFuturesHttpService} so every response stays a {@code Map}.
 */
public final class BinanceOrderMapper {

    /** Binance {@code newClientOrderId} max length (regex {@code ^[.A-Za-z0-9:/_-]{1,36}$}). */
    public static final int MAX_CLIENT_ORDER_ID = 36;

    private BinanceOrderMapper() {}

    // ---- Symbols ----

    /** Core symbol ({@code BTCUSDT}) -> Binance symbol. Binance uses no separator, so this is identity. */
    public static String toBinanceSymbol(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("symbol is null");
        }
        return symbol.trim().toUpperCase(Locale.ROOT).replace("-", "");
    }

    /** Binance symbol -> core symbol (identity; both are dash-free upper-case). */
    public static String toCoreSymbol(String binanceSymbol) {
        return binanceSymbol == null ? null : binanceSymbol.toUpperCase(Locale.ROOT).replace("-", "");
    }

    /**
     * Clamp a clientOrderId to Binance's 36-char limit. Keeps the LEADING chars, not the trailing
     * ones: the platform's own id scheme is {@code shortId(proposalId) + "-" + accountId}
     * ({@code ProposalExecutionService}) — the unique part is the prefix, and accountId (constant
     * per account) is the suffix. Keeping the tail on a long accountId would collapse every id
     * from that account into the same 36-char string, making a genuinely different order look like
     * a duplicate {@code newClientOrderId} and misdirecting later {@code getOrderStatus}/
     * {@code cancelOrder} calls at the wrong order. Returns null unchanged.
     */
    public static String clampClientOrderId(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.length() <= MAX_CLIENT_ORDER_ID) {
            return clientOrderId;
        }
        return clientOrderId.substring(0, MAX_CLIENT_ORDER_ID);
    }

    // ---- Order state ----

    /** Maps a Binance order status string into {@link AdapterOrderState}. Null/unknown -> NEW. */
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

    /**
     * Rows out of a Binance array response wrapped under {@code "data"}
     * ({@code /positionRisk}, {@code /balance}, {@code /openOrders}). Tolerant: a missing/malformed
     * shape yields an empty list rather than throwing.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> dataRows(Map<String, Object> resp) {
        if (resp == null) {
            return List.of();
        }
        Object data = resp.get("data");
        if (!(data instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }
}
