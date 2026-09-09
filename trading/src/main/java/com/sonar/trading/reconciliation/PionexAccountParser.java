package com.sonar.trading.reconciliation;

import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangePosition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pure parsing of Pionex account responses into typed reconciliation models — mirrors
 * {@link BingxAccountParser}'s tolerant style (multiple candidate field names per value).
 *
 * <p>Field names confirmed against a live account call (2026-09-07), not guessed:
 * <pre>
 * GET /uapi/v1/account/balances → data.balances[]: {coin, free, frozen, debts}
 *   — a coin-by-coin WALLET, not a margin-account summary: no equity/unrealizedPnl at this level.
 * GET /uapi/v1/account/positions → data.positions[]: {symbol, positionSide, netSize, avgPrice,
 *   markPrice, unrealizedPnL (capital L), leverage, liquidationPrice, ...}
 * </pre>
 * Since {@code balances} carries no PnL, {@link PionexAccountReader#getBalance()} sums
 * {@code unrealizedPnL} across positions itself and folds it into {@link ExchangeBalance}, so the
 * existing {@code balance + unrealizedPnl} equity fallback ({@code MinutePoller.pollAccount}) still
 * works the same way it does for BingX/Bybit.
 */
public final class PionexAccountParser {

    private PionexAccountParser() {}

    // ---- Positions: GET /uapi/v1/account/positions → data.positions[] ----

    public static List<ExchangePosition> parsePositions(Map<String, Object> resp) {
        List<ExchangePosition> out = new ArrayList<>();
        for (Map<String, Object> row : rowsUnder(resp, "positions")) {
            ExchangePosition p = parsePositionRow(row);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private static ExchangePosition parsePositionRow(Map<String, Object> row) {
        String rawSymbol = str(row, "symbol");
        if (rawSymbol == null) {
            return null;
        }
        // BTC_PUSD_PERP (paper-trading) and BTC_USDT_PERP (real) both collapse to the core symbol
        // BTCUSDT — fine for reading a single position, but adoptUntracked matches live positions
        // to DB rows BY CORE SYMBOL, so if this account ever holds both at once, the second parsed
        // here would overwrite the first's qty/side/entry in the very same row. This reader exists
        // to reflect the account's real state, not the demo pool sitting unused beside it — drop
        // paper contracts here rather than let two different positions fight over one DB row.
        if (rawSymbol.toUpperCase(Locale.ROOT).contains("_PUSD_")) {
            return null;
        }
        // Pionex symbols are underscore-form (BTC_USDT_PERP / BTC_PUSD_PERP) — normalize to the
        // platform's core form (BTCUSDT) by dropping the quote-asset/contract-type segments.
        String symbol = toCoreSymbol(rawSymbol);

        // netSize is the position's own size, unsigned — side comes from the explicit
        // positionSide field (LONG/SHORT), confirmed present on every row; the sign-inference
        // fallback below only matters if a future response ever omits it.
        BigDecimal size = dec(row, "netSize", "size", "positionAmt", "amount", "qty");
        BigDecimal qty = size == null ? null : size.abs();
        String side = normalizeSide(str(row, "positionSide", "side"), size);

        return new ExchangePosition(
                symbol,
                side,
                qty,
                dec(row, "avgPrice", "entryPrice", "openAvgPrice", "avgCost"),
                dec(row, "markPrice", "mark_price"),
                dec(row, "unrealizedPnL", "unrealizedPnl", "unrealizedProfit", "unrealisedPnl"),
                intVal(row, "leverage"),
                dec(row, "liquidationPrice", "liqPrice")
        );
    }

    /** LONG / SHORT / FLAT. Falls back to the sign of the size when the side field is absent/BOTH. */
    private static String normalizeSide(String side, BigDecimal size) {
        String s = side == null ? null : side.trim().toUpperCase(Locale.ROOT);
        if ("LONG".equals(s)) {
            return "LONG";
        }
        if ("SHORT".equals(s)) {
            return "SHORT";
        }
        if (size == null || size.signum() == 0) {
            return "FLAT";
        }
        return size.signum() > 0 ? "LONG" : "SHORT";
    }

    /** {@code BTC_USDT_PERP} / {@code BTC_PUSD_PERP} → {@code BTCUSDT} (demo PUSD pairs collapse
     *  onto the same core symbol as their USDT twin — same convention v1 used for mark-price mid). */
    static String toCoreSymbol(String pionexSymbol) {
        String[] parts = pionexSymbol.split("_");
        return parts.length >= 2 ? (parts[0] + "USDT").toUpperCase(Locale.ROOT)
                : pionexSymbol.toUpperCase(Locale.ROOT);
    }

    // ---- Balance: GET /uapi/v1/account/balances → data.balances[] (a coin wallet, not a margin
    // summary — {coin, free, frozen, debts}; no equity/PnL here, see the unrealized-PnL fold-in
    // done by PionexAccountReader.getBalance()) ----

    /** Quote-like assets worth falling back to when the requested one is absent — see the fallback
     *  comment below for why this list exists at all instead of "any row". */
    private static final java.util.Set<String> FALLBACK_ASSETS = java.util.Set.of("USDT", "PUSD", "USDC");

    public static Optional<ExchangeBalance> parseBalance(Map<String, Object> resp, String asset) {
        List<Map<String, Object>> rows = rowsUnder(resp, "balances");
        String want = asset == null ? null : asset.toUpperCase(Locale.ROOT);

        for (Map<String, Object> row : rows) {
            String a = str(row, "coin", "asset", "currency");
            // a == null used to match here too, which meant a malformed row with no coin field at
            // all silently impersonated whatever asset was requested — worse than admitting we
            // don't know, since the number then flows into equity/daily_baseline/% sizing as if it
            // were real USDT.
            if (a != null && want != null && want.equals(a.toUpperCase(Locale.ROOT))) {
                return Optional.of(parseBalanceRow(a, row));
            }
        }
        // Requested asset absent — fall back only to another quote-like asset (this account holds
        // a separate PUSD paper-trading pool alongside real USDT), never to "whichever row happens
        // to be first". Unlike BingX's VST fallback (a same-scale demo twin of the requested
        // asset), Pionex's balances are a plain coin wallet — rows.get(0) can just as easily be
        // 0.003 BTC, and reporting that as "equity" would size a % position off a number two
        // orders of magnitude away from the truth.
        for (Map<String, Object> row : rows) {
            String a = str(row, "coin", "asset", "currency");
            if (a != null && FALLBACK_ASSETS.contains(a.toUpperCase(Locale.ROOT))) {
                return Optional.of(parseBalanceRow(a, row));
            }
        }
        return Optional.empty();
    }

    /**
     * {@code balance}/{@code equity} both start as {@code free} (available, unlocked) — the wallet
     * has no margin-account "total equity" field, and {@code frozen} here read 0 even with open
     * cross-margin positions live, so it isn't "margin currently in use" either. {@code equity} gets
     * corrected upstream in {@code PionexAccountReader.getBalance()} once position PnL is folded in
     * (equity = free + Σ unrealizedPnL, the same shape {@code MinutePoller}'s own fallback uses).
     */
    private static ExchangeBalance parseBalanceRow(String asset, Map<String, Object> row) {
        BigDecimal free = dec(row, "free", "balance", "walletBalance", "total");
        return new ExchangeBalance(
                asset,
                free,
                free,
                null,
                free,
                dec(row, "frozen", "used", "usedBalance", "locked")
        );
    }

    // ---- Low-level helpers ----

    /** {@code data.<key>[]} rows, tolerating a bare {@code data[]} or single-object shape too. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsUnder(Map<String, Object> resp, String key) {
        Object data = resp == null ? null : resp.get("data");
        Object list = data instanceof Map<?, ?> dm ? dm.get(key) : null;
        if (list == null) {
            list = data; // tolerate the row list living directly under "data"
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (list instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    rows.add((Map<String, Object>) m);
                }
            }
        } else if (list instanceof Map<?, ?> m) {
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
