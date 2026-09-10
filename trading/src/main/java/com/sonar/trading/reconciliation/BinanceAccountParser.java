package com.sonar.trading.reconciliation;

import com.sonar.trading.exchange.binance.BinanceOrderMapper;
import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangePosition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pure parsing of Binance USDT-M Futures account responses into typed reconciliation models —
 * mirrors {@link BybitAccountParser} / {@link BingxAccountParser}. No mutation, no I/O.
 */
public final class BinanceAccountParser {

    private BinanceAccountParser() {}

    // ---- Positions: GET /fapi/v2/positionRisk (wrapped under "data") ----

    /** Parse the positions response into core-symbol positions. FLAT (zero-amount) rows are kept. */
    public static List<ExchangePosition> parsePositions(Map<String, Object> resp) {
        return BinanceOrderMapper.dataRows(resp).stream()
                .map(BinanceAccountParser::parsePositionRow)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** First position matching the given core symbol, if any (zero-amount rows treated as flat). */
    public static Optional<ExchangePosition> findPosition(Map<String, Object> resp, String coreSymbol) {
        if (coreSymbol == null) {
            return Optional.empty();
        }
        String want = coreSymbol.toUpperCase(Locale.ROOT);
        return parsePositions(resp).stream()
                .filter(p -> want.equals(p.symbol()))
                .reduce((a, b) -> a.isFlat() ? b : a);
    }

    private static ExchangePosition parsePositionRow(Map<String, Object> row) {
        String rawSymbol = str(row, "symbol");
        if (rawSymbol == null) {
            return null;
        }
        String symbol = BinanceOrderMapper.toCoreSymbol(rawSymbol);

        BigDecimal amt = dec(row, "positionAmt");
        BigDecimal qty = amt == null ? null : amt.abs();
        String side = normalizeSide(amt);

        return new ExchangePosition(
                symbol,
                side,
                qty,
                dec(row, "entryPrice"),
                dec(row, "markPrice"),
                dec(row, "unRealizedProfit", "unrealizedProfit"),
                intVal(row, "leverage"),
                dec(row, "liquidationPrice")
        );
    }

    /** LONG / SHORT / FLAT. One-Way Mode has no explicit side field — derived from the signed amount. */
    private static String normalizeSide(BigDecimal amt) {
        if (amt == null || amt.signum() == 0) {
            return "FLAT";
        }
        return amt.signum() > 0 ? "LONG" : "SHORT";
    }

    // ---- Balance: GET /fapi/v2/balance (wrapped under "data") ----

    /** Parse the balance for one asset (e.g. {@code USDT}). Falls back to the first row if not found. */
    public static Optional<ExchangeBalance> parseBalance(Map<String, Object> resp, String asset) {
        List<Map<String, Object>> rows = BinanceOrderMapper.dataRows(resp);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String want = asset == null ? null : asset.toUpperCase(Locale.ROOT);
        for (Map<String, Object> row : rows) {
            String a = str(row, "asset");
            // A row with no/blank asset label can't be a genuine match by name — matching it here
            // let a row for the WRONG asset (a partial/degraded response mixing a blank-asset row
            // in with real ones) get labeled and returned as the requested one.
            if (a == null) {
                continue;
            }
            if (want == null || want.equals(a.toUpperCase(Locale.ROOT))) {
                return Optional.of(parseBalanceRow(a, row));
            }
        }
        return Optional.of(parseBalanceRow(str(rows.get(0), "asset"), rows.get(0)));
    }

    private static ExchangeBalance parseBalanceRow(String asset, Map<String, Object> row) {
        BigDecimal balance = dec(row, "balance");
        BigDecimal unrealized = dec(row, "crossUnPnl", "unrealizedProfit");
        BigDecimal equity = balance != null && unrealized != null ? balance.add(unrealized) : balance;
        return new ExchangeBalance(
                asset,
                balance,
                equity,
                unrealized,
                dec(row, "availableBalance"),
                // Used margin isn't on /fapi/v2/balance (it's positionInitialMargin on
                // /fapi/v2/account, a call this reader doesn't make) — left null rather than
                // approximated from a field that doesn't actually represent it.
                null
        );
    }

    // ---- Low-level helpers ----

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
