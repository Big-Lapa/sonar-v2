package com.sonar.trading.reconciliation;

import com.sonar.trading.exchange.bingx.BingxOrderMapper;
import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangePosition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pure parsing of BingX account responses into typed reconciliation models (T17).
 *
 * <p>Tolerant by design (BingX wraps payloads under {@code data}, returns either arrays or single
 * objects, and uses both British/American spellings like {@code unrealisedProfit}). Mirrors the
 * defensive style of {@link BingxOrderMapper}. No mutation, no I/O.
 */
public final class BingxAccountParser {

    private BingxAccountParser() {}

    // ---- Positions: GET /openApi/swap/v2/user/positions ----

    /** Parse the positions response into core-symbol positions. FLAT (zero-amount) rows are kept. */
    public static List<ExchangePosition> parsePositions(Map<String, Object> resp) {
        List<ExchangePosition> out = new ArrayList<>();
        for (Map<String, Object> row : asRows(resp == null ? null : resp.get("data"))) {
            ExchangePosition p = parsePositionRow(row);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** First position matching the given core symbol, if any (zero-amount rows treated as flat). */
    public static Optional<ExchangePosition> findPosition(Map<String, Object> resp, String coreSymbol) {
        if (coreSymbol == null) {
            return Optional.empty();
        }
        String want = coreSymbol.toUpperCase(Locale.ROOT);
        return parsePositions(resp).stream()
                .filter(p -> want.equals(p.symbol()))
                .reduce((a, b) -> a.isFlat() ? b : a); // prefer a non-flat row if the venue lists both legs
    }

    private static ExchangePosition parsePositionRow(Map<String, Object> row) {
        String rawSymbol = str(row, "symbol");
        if (rawSymbol == null) {
            return null;
        }
        String symbol = BingxOrderMapper.toCoreSymbol(rawSymbol).toUpperCase(Locale.ROOT);

        BigDecimal amt = dec(row, "positionAmt", "positionAmtStr", "amount");
        BigDecimal qty = amt == null ? null : amt.abs();

        String side = normalizeSide(str(row, "positionSide", "side"), amt);

        return new ExchangePosition(
                symbol,
                side,
                qty,
                dec(row, "avgPrice", "entryPrice", "openAvgPrice"),
                dec(row, "markPrice"),
                dec(row, "unrealizedProfit", "unrealisedProfit", "unrealizedPnl"),
                intVal(row, "leverage"),
                dec(row, "liquidationPrice", "liqPrice")
        );
    }

    /** LONG / SHORT / FLAT. BOTH (one-way mode) resolves from the signed amount. */
    private static String normalizeSide(String positionSide, BigDecimal amt) {
        String s = positionSide == null ? null : positionSide.trim().toUpperCase(Locale.ROOT);
        if ("LONG".equals(s)) {
            return "LONG";
        }
        if ("SHORT".equals(s)) {
            return "SHORT";
        }
        // BOTH / null / unknown → derive from the sign of the amount.
        if (amt == null || amt.signum() == 0) {
            return "FLAT";
        }
        return amt.signum() > 0 ? "LONG" : "SHORT";
    }

    // ---- Balance: GET /openApi/swap/v3/user/balance ----

    /**
     * Parse the balance for one asset (e.g. {@code USDT}). Handles the V3 array shape
     * ({@code data:[{asset,...}]}), a single object ({@code data:{...}}) and the legacy
     * {@code data:{balance:{...}}} wrapper.
     *
     * <p>Fallback (BUG-3/T22): if the requested asset is not present but the venue still returned a
     * balance row, the single returned row is used as-is. The BingX VST demo settles {@code VST}
     * while config asks for {@code USDT}; without this, equity/PnL/{@code balance.updated} silently
     * no-op'd. Empty data still yields empty.
     */
    public static Optional<ExchangeBalance> parseBalance(Map<String, Object> resp, String asset) {
        Object data = resp == null ? null : resp.get("data");
        String want = asset == null ? null : asset.toUpperCase(Locale.ROOT);

        List<Map<String, Object>> rows = asRows(unwrapBalance(data));
        for (Map<String, Object> row : rows) {
            String a = str(row, "asset", "currency");
            if (want == null || a == null || want.equals(a.toUpperCase(Locale.ROOT))) {
                return Optional.of(parseBalanceRow(a == null ? want : a, row));
            }
        }
        // Requested asset absent but a balance row exists → fall back to it (e.g. VST demo).
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            return Optional.of(parseBalanceRow(str(row, "asset", "currency"), row));
        }
        return Optional.empty();
    }

    private static ExchangeBalance parseBalanceRow(String asset, Map<String, Object> row) {
        BigDecimal balance = dec(row, "balance", "walletBalance", "equity");
        BigDecimal equity = dec(row, "equity", "marginBalance");
        BigDecimal unrealized = dec(row, "unrealizedProfit", "unrealisedProfit", "unrealizedPnl");
        return new ExchangeBalance(
                asset,
                balance,
                equity,
                unrealized,
                dec(row, "availableMargin", "available", "availableBalance"),
                dec(row, "usedMargin", "used", "usedBalance")
        );
    }

    /** Legacy {@code {balance:{...}}} wrapper → the inner object; otherwise pass through. */
    private static Object unwrapBalance(Object data) {
        if (data instanceof Map<?, ?> m && m.get("balance") instanceof Map<?, ?> inner) {
            return inner;
        }
        return data;
    }

    // ---- Low-level helpers ----

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRows(Object data) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (data instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    rows.add((Map<String, Object>) m);
                }
            }
        } else if (data instanceof Map<?, ?> m) {
            rows.add((Map<String, Object>) m);
        }
        return rows;
    }

    private static String str(Map<String, Object> row, String... keys) {
        for (String k : keys) {
            Object v = row.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return String.valueOf(v);
            }
        }
        return null;
    }

    private static BigDecimal dec(Map<String, Object> row, String... keys) {
        String s = str(row, keys);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intVal(Map<String, Object> row, String... keys) {
        BigDecimal d = dec(row, keys);
        return d == null ? null : d.intValue();
    }
}
