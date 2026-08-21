package com.sonar.trading.reconciliation;

import com.sonar.trading.exchange.binance.BinanceOrderMapper;
import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangePosition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pure parsing of Binance USDT-M Futures account responses into the typed reconciliation models
 * (P4) — the Binance analogue of {@link BingxAccountParser}.
 *
 * <p>Both endpoints return JSON arrays wrapped under {@code "data"} by
 * {@code BinanceFuturesHttpService}. Positions come from {@code /fapi/v2/positionRisk}
 * ({@code positionAmt}, {@code entryPrice}, {@code unRealizedProfit}, …); balances from
 * {@code /fapi/v2/balance} ({@code asset}, {@code balance}, {@code crossUnPnl},
 * {@code availableBalance}). Symbols are already core form ({@code BTCUSDT}). No mutation, no I/O.
 */
public final class BinanceAccountParser {

    private BinanceAccountParser() {}

    // ---- Positions: GET /fapi/v2/positionRisk ----

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
        String symbol = BinanceOrderMapper.toCoreSymbol(rawSymbol).toUpperCase(Locale.ROOT);

        BigDecimal amt = dec(row, "positionAmt");
        BigDecimal qty = amt == null ? null : amt.abs();
        String side = normalizeSide(str(row, "positionSide"), amt);

        return new ExchangePosition(
                symbol,
                side,
                qty,
                dec(row, "entryPrice"),
                dec(row, "markPrice"),
                dec(row, "unRealizedProfit", "unrealizedProfit", "unrealizedPnl"),
                intVal(row, "leverage"),
                dec(row, "liquidationPrice")
        );
    }

    /** LONG / SHORT / FLAT. BOTH (one-way mode) resolves from the signed positionAmt. */
    private static String normalizeSide(String positionSide, BigDecimal amt) {
        String s = positionSide == null ? null : positionSide.trim().toUpperCase(Locale.ROOT);
        if ("LONG".equals(s)) {
            return "LONG";
        }
        if ("SHORT".equals(s)) {
            return "SHORT";
        }
        if (amt == null || amt.signum() == 0) {
            return "FLAT";
        }
        return amt.signum() > 0 ? "LONG" : "SHORT";
    }

    // ---- Balance: GET /fapi/v2/balance ----

    /**
     * Parse the balance for one asset (e.g. {@code USDT}). Binance returns one row per asset;
     * {@code balance} is the wallet balance and {@code crossUnPnl} the cross-margin unrealized PnL.
     * Falls back to the single returned row if the requested asset is absent (mirrors BingX).
     */
    public static Optional<ExchangeBalance> parseBalance(Map<String, Object> resp, String asset) {
        String want = asset == null ? null : asset.toUpperCase(Locale.ROOT);
        List<Map<String, Object>> rows = asRows(resp == null ? null : resp.get("data"));
        for (Map<String, Object> row : rows) {
            String a = str(row, "asset");
            if (want == null || a == null || want.equals(a.toUpperCase(Locale.ROOT))) {
                return Optional.of(parseBalanceRow(a == null ? want : a, row));
            }
        }
        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            return Optional.of(parseBalanceRow(str(row, "asset"), row));
        }
        return Optional.empty();
    }

    private static ExchangeBalance parseBalanceRow(String asset, Map<String, Object> row) {
        BigDecimal balance = dec(row, "balance", "walletBalance", "crossWalletBalance");
        BigDecimal unrealized = dec(row, "crossUnPnl", "unRealizedProfit", "unrealizedProfit");
        BigDecimal available = dec(row, "availableBalance", "maxWithdrawAmount");
        return new ExchangeBalance(asset, balance, null, unrealized, available, null);
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
