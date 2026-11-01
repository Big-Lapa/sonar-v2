package com.sonar.trading.reconciliation;

import com.sonar.trading.reconciliation.model.ExchangePosition;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

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
 * The wallet is valued in USDT coin by coin ({@link #valueWallet}, non-stable coins at the public
 * {@code /api/v1/market/bookTickers} bid — {@link #parseBookTicker}).
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

    /** Counted 1:1 in USDT, never priced. */
    private static final Set<String> STABLECOINS = Set.of("USDT", "USDC");
    /** Pionex's paper-trading pool (~500k sits next to the real coins) — never real money. */
    private static final String PAPER_COIN = "PUSD";

    /** A coin's USDT price: the book-ticker bid, or "this coin has no {@code <COIN>_USDT} pair". */
    public sealed interface CoinPrice {
        record Bid(BigDecimal price) implements CoinPrice {}
        record NoPair() implements CoinPrice {}
    }

    /**
     * The futures wallet valued in USDT.
     *
     * @param total         Σ (free + frozen − debts) × price over every counted coin
     * @param freeUsdt      free USDT only — the money actually spendable as margin right now
     * @param unpricedCoins non-zero coins left out because they have no USDT pair
     */
    public record WalletValue(BigDecimal total, BigDecimal freeUsdt, List<String> unpricedCoins) {}

    /**
     * Values every coin except {@code PUSD}: stablecoins 1:1, anything else at {@code priceOf(coin)}.
     * Zero rows are skipped without asking for a price. A coin with no USDT pair ({@link
     * CoinPrice.NoPair}, e.g. dust) is left out and reported; a failing {@code priceOf} propagates —
     * a transiently missing BTC price must make the balance unknown, not silently drop $200 out of
     * equity (that would read as an ANOMALY_EQUITY drop and a fake drawdown). Rows without a coin
     * field are ignored rather than counted as some asset. Empty when the wallet has no rows at all.
     */
    public static Optional<WalletValue> valueWallet(Map<String, Object> resp, Function<String, CoinPrice> priceOf) {
        List<Map<String, Object>> rows = rowsUnder(resp, "balances");
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal freeUsdt = BigDecimal.ZERO;
        List<String> unpriced = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String raw = str(row, "coin", "asset", "currency");
            if (raw == null) {
                continue;
            }
            String coin = raw.toUpperCase(Locale.ROOT);
            if (PAPER_COIN.equals(coin)) {
                continue;
            }
            BigDecimal free = orZero(dec(row, "free"));
            if ("USDT".equals(coin)) {
                freeUsdt = freeUsdt.add(free);
            }
            BigDecimal qty = free.add(orZero(dec(row, "frozen"))).subtract(orZero(dec(row, "debts")));
            if (qty.signum() == 0) {
                continue;
            }
            if (STABLECOINS.contains(coin)) {
                total = total.add(qty);
                continue;
            }
            switch (priceOf.apply(coin)) {
                case CoinPrice.Bid bid -> total = total.add(qty.multiply(bid.price()));
                case CoinPrice.NoPair noPair -> unpriced.add(coin);
            }
        }
        return Optional.of(new WalletValue(total, freeUsdt, List.copyOf(unpriced)));
    }

    // ---- Book ticker: GET /api/v1/market/bookTickers?symbol=<COIN>_USDT → data.tickers[] ----

    /**
     * The bid out of a raw book-ticker response. {@code result:false} with {@code
     * MARKET_INVALID_SYMBOL} (confirmed live) means the pair does not exist → {@link CoinPrice.NoPair}.
     * Any other error, an empty ticker list or a non-positive bid is a failed call, not a missing pair,
     * and throws.
     */
    public static CoinPrice parseBookTicker(Map<String, Object> resp) {
        if (resp == null) {
            throw new IllegalStateException("Pionex book ticker: empty response");
        }
        Object result = resp.get("result");
        if (!Boolean.TRUE.equals(result) && !"true".equals(String.valueOf(result))) {
            if ("MARKET_INVALID_SYMBOL".equals(String.valueOf(resp.get("code")))) {
                return new CoinPrice.NoPair();
            }
            throw new IllegalStateException("Pionex book ticker failed: code=" + resp.get("code")
                    + " message=" + resp.get("message"));
        }
        List<Map<String, Object>> tickers = rowsUnder(resp, "tickers");
        BigDecimal bid = tickers.isEmpty() ? null : dec(tickers.get(0), "bidPrice");
        if (bid == null || bid.signum() <= 0) {
            throw new IllegalStateException("Pionex book ticker has no usable bid: " + resp);
        }
        return new CoinPrice.Bid(bid);
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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
