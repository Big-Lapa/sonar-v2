package com.sonar.trading.reconciliation;

import com.sonar.trading.exchange.bybit.BybitOrderMapper;
import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangePosition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pure parsing of Bybit V5 account responses into typed reconciliation models — mirrors
 * {@link com.sonar.trading.reconciliation.BingxAccountParser}. No mutation, no I/O.
 */
public final class BybitAccountParser {

    private BybitAccountParser() {}

    // ---- Positions: GET /v5/position/list (result.list) ----

    /** Parse the positions response into core-symbol positions. FLAT (zero-size) rows are kept. */
    public static List<ExchangePosition> parsePositions(Map<String, Object> resp) {
        return BybitOrderMapper.resultList(resp).stream()
                .map(BybitAccountParser::parsePositionRow)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** First position matching the given core symbol, if any (zero-size rows treated as flat). */
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
        String symbol = BybitOrderMapper.toCoreSymbol(rawSymbol);

        BigDecimal size = dec(row, "size");
        BigDecimal qty = size == null ? null : size.abs();
        String side = normalizeSide(str(row, "side"), size);

        return new ExchangePosition(
                symbol,
                side,
                qty,
                dec(row, "avgPrice"),
                dec(row, "markPrice"),
                dec(row, "unrealisedPnl", "unrealizedPnl"),
                intVal(row, "leverage"),
                dec(row, "liqPrice")
        );
    }

    /** LONG / SHORT / FLAT. Bybit's {@code side} is "Buy"/"Sell"/"" (blank = flat, one-way mode). */
    private static String normalizeSide(String side, BigDecimal size) {
        String s = side == null ? null : side.trim().toUpperCase(Locale.ROOT);
        if ("BUY".equals(s)) {
            return "LONG";
        }
        if ("SELL".equals(s)) {
            return "SHORT";
        }
        if (size == null || size.signum() == 0) {
            return "FLAT";
        }
        return size.signum() > 0 ? "LONG" : "SHORT";
    }

    // ---- Balance: GET /v5/account/wallet-balance (result.list[0].coin[]) ----

    /**
     * Parse the balance for one asset (e.g. {@code USDT}). Bybit's Unified account nests coin
     * balances one level deeper than BingX: {@code result.list[0].coin[{coin,...}]}, with the
     * account-level totals ({@code totalEquity}) sitting alongside on {@code result.list[0]}.
     */
    public static Optional<ExchangeBalance> parseBalance(Map<String, Object> resp, String asset) {
        List<Map<String, Object>> accounts = BybitOrderMapper.resultList(resp);
        if (accounts.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> account = accounts.get(0);
        Object coinObj = account.get("coin");
        List<Map<String, Object>> coins = asRows(coinObj);
        String want = asset == null ? null : asset.toUpperCase(Locale.ROOT);

        for (Map<String, Object> row : coins) {
            String c = str(row, "coin");
            if (want == null || c == null || want.equals(c.toUpperCase(Locale.ROOT))) {
                return Optional.of(parseBalanceRow(c == null ? want : c, row, account));
            }
        }
        if (!coins.isEmpty()) {
            Map<String, Object> row = coins.get(0);
            return Optional.of(parseBalanceRow(str(row, "coin"), row, account));
        }
        return Optional.empty();
    }

    private static ExchangeBalance parseBalanceRow(String asset, Map<String, Object> coinRow, Map<String, Object> account) {
        BigDecimal balance = dec(coinRow, "walletBalance");
        BigDecimal equity = dec(coinRow, "equity");
        if (equity == null) {
            equity = dec(account, "totalEquity");
        }
        return new ExchangeBalance(
                asset,
                balance,
                equity,
                dec(coinRow, "unrealisedPnl", "unrealizedPnl"),
                dec(coinRow, "availableToWithdraw", "availableBalance"),
                dec(coinRow, "totalPositionIM", "usedMargin")
        );
    }

    // ---- Low-level helpers ----

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRows(Object data) {
        if (data instanceof List<?> list) {
            return list.stream().filter(o -> o instanceof Map<?, ?>).map(o -> (Map<String, Object>) o).toList();
        }
        if (data instanceof Map<?, ?> m) {
            return List.of((Map<String, Object>) m);
        }
        return List.of();
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
