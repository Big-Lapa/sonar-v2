package com.sonar.trading.reconciliation;

import com.sonar.trading.reconciliation.PionexAccountParser.CoinPrice;
import com.sonar.trading.reconciliation.PionexAccountParser.WalletValue;
import com.sonar.trading.reconciliation.model.ExchangePosition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fixtures copied verbatim from a live Pionex account (2026-09-07) — the field names here were
 * confirmed against the real API, not guessed from another venue's shape: the first attempt used
 * BingX-style names ({@code size}, {@code unrealizedPnl}) and silently parsed every number to null.
 */
class PionexAccountParserTest {

    /** GET /uapi/v1/account/balances — a coin wallet, not a margin summary: no equity, no PnL. */
    private static Map<String, Object> balancesResponse() {
        return Map.of("result", true, "data", Map.of("balances", List.of(
                Map.of("coin", "BTC", "free", "0.003", "frozen", "0", "debts", "0"),
                Map.of("coin", "USDT", "free", "135.75090540", "frozen", "0", "debts", "0"),
                Map.of("coin", "PUSD", "free", "499988.6568433", "frozen", "0", "debts", "0"))));
    }

    /** GET /uapi/v1/account/positions — note {@code netSize} and the capital L in unrealizedPnL. */
    private static Map<String, Object> positionsResponse() {
        return Map.of("result", true, "data", Map.of("positions", List.of(
                Map.of("symbol", "ADA_USDT_PERP", "positionSide", "LONG", "netSize", "919",
                        "avgPrice", "0.2175", "markPrice", "0.2211223", "leverage", "20",
                        "liquidationPrice", "0", "unrealizedPnL", "3.3288937"),
                Map.of("symbol", "BTC_USDT_PERP", "positionSide", "LONG", "netSize", "0.0088",
                        "avgPrice", "79264.1", "markPrice", "79058.7", "leverage", "70",
                        "liquidationPrice", "37472.3", "unrealizedPnL", "-1.80752"))));
    }

    // ---- Wallet valuation: every coin except the PUSD paper pool, in USDT ----

    /** Price lookup that records which coins were asked for; unknown coins have no USDT pair. */
    private static final class Prices implements Function<String, CoinPrice> {
        private final Map<String, String> bids;
        final List<String> asked = new ArrayList<>();

        Prices(Map<String, String> bids) { this.bids = bids; }

        @Override
        public CoinPrice apply(String coin) {
            asked.add(coin);
            String bid = bids.get(coin);
            return bid == null ? new CoinPrice.NoPair() : new CoinPrice.Bid(new BigDecimal(bid));
        }
    }

    @Test
    void valuesNonStableCoinsAtTheirBidAndStablecoinsOneToOne() {
        Map<String, Object> resp = Map.of("result", true, "data", Map.of("balances", List.of(
                Map.of("coin", "BTC", "free", "0.003", "frozen", "0", "debts", "0"),
                Map.of("coin", "USDT", "free", "100", "frozen", "0", "debts", "0"),
                Map.of("coin", "USDC", "free", "5", "frozen", "0", "debts", "0"))));
        Prices prices = new Prices(Map.of("BTC", "78000"));

        WalletValue value = PionexAccountParser.valueWallet(resp, prices).orElseThrow();

        // 0.003 * 78000 + 100 + 5
        assertThat(value.total()).isEqualByComparingTo("339");
        assertThat(value.freeUsdt()).isEqualByComparingTo("100");
        assertThat(value.unpricedCoins()).isEmpty();
        assertThat(prices.asked).containsExactly("BTC"); // stablecoins never hit the ticker
    }

    @Test
    void thePusdPaperPoolIsNeverCounted() {
        // The live wallet carries ~500k PUSD of paper-trading money next to the real coins.
        Prices prices = new Prices(Map.of("BTC", "78000"));

        WalletValue value = PionexAccountParser.valueWallet(balancesResponse(), prices).orElseThrow();

        // 0.003 * 78000 + 135.75090540 — no PUSD
        assertThat(value.total()).isEqualByComparingTo("369.75090540");
        assertThat(prices.asked).doesNotContain("PUSD");
    }

    @Test
    void quantityIsFreePlusFrozenMinusDebtsForEveryCoinIncludingUsdt() {
        Map<String, Object> resp = Map.of("result", true, "data", Map.of("balances", List.of(
                Map.of("coin", "BTC", "free", "0.002", "frozen", "0.001", "debts", "0.0005"),
                Map.of("coin", "USDT", "free", "100", "frozen", "20", "debts", "10"))));

        WalletValue value = PionexAccountParser.valueWallet(resp, new Prices(Map.of("BTC", "80000"))).orElseThrow();

        // (0.002 + 0.001 - 0.0005) * 80000 + (100 + 20 - 10)
        assertThat(value.total()).isEqualByComparingTo("310");
        // available money is the free USDT only — frozen is tied up, debts are not spendable
        assertThat(value.freeUsdt()).isEqualByComparingTo("100");
    }

    @Test
    void zeroRowsAreSkippedWithoutAskingForAPrice() {
        // The live wallet today: USDT row present but 0, the money sits in BTC.
        Map<String, Object> resp = Map.of("result", true, "data", Map.of("balances", List.of(
                Map.of("coin", "USDT", "free", "0", "frozen", "0", "debts", "0"),
                Map.of("coin", "ETH", "free", "0", "frozen", "0", "debts", "0"),
                Map.of("coin", "BTC", "free", "0.003", "frozen", "0", "debts", "0"))));
        Prices prices = new Prices(Map.of("BTC", "78000"));

        WalletValue value = PionexAccountParser.valueWallet(resp, prices).orElseThrow();

        assertThat(value.total()).isEqualByComparingTo("234");
        assertThat(value.freeUsdt()).isEqualByComparingTo("0");
        assertThat(prices.asked).containsExactly("BTC");
    }

    @Test
    void aCoinWithNoUsdtPairIsLeftOutAndReportedRatherThanBreakingTheWholeWallet() {
        Map<String, Object> resp = Map.of("result", true, "data", Map.of("balances", List.of(
                Map.of("coin", "BTC", "free", "0.003", "frozen", "0", "debts", "0"),
                Map.of("coin", "DUSTCOIN", "free", "12.5", "frozen", "0", "debts", "0"))));

        WalletValue value = PionexAccountParser.valueWallet(resp, new Prices(Map.of("BTC", "78000"))).orElseThrow();

        assertThat(value.total()).isEqualByComparingTo("234");
        assertThat(value.unpricedCoins()).containsExactly("DUSTCOIN");
    }

    @Test
    void aFailedPriceLookupMakesTheWholeWalletUnknown() {
        // A transient ticker failure must not yield a confidently low equity (BTC silently
        // dropped -> $234 becomes $0 -> ANOMALY_EQUITY, a fake drawdown).
        Map<String, Object> resp = Map.of("result", true, "data", Map.of("balances", List.of(
                Map.of("coin", "BTC", "free", "0.003", "frozen", "0", "debts", "0"))));
        Function<String, CoinPrice> failing = coin -> { throw new IllegalStateException("ticker down"); };

        assertThatThrownBy(() -> PionexAccountParser.valueWallet(resp, failing))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aRowWithNoCoinFieldIsIgnored() {
        // A malformed row (missing "coin") must never be counted as some asset.
        Map<String, Object> resp = Map.of("result", true, "data", Map.of("balances", List.of(
                Map.of("free", "999999", "frozen", "0", "debts", "0"),
                Map.of("coin", "USDT", "free", "10", "frozen", "0", "debts", "0"))));

        WalletValue value = PionexAccountParser.valueWallet(resp, new Prices(Map.of())).orElseThrow();

        assertThat(value.total()).isEqualByComparingTo("10");
    }

    @Test
    void emptyOrMissingWalletYieldsNoValue() {
        Prices prices = new Prices(Map.of());
        assertThat(PionexAccountParser.valueWallet(Map.of("result", true, "data",
                Map.of("balances", List.of())), prices)).isEmpty();
        assertThat(PionexAccountParser.valueWallet(null, prices)).isEmpty();
    }

    // ---- Book ticker: GET /api/v1/market/bookTickers?symbol=<COIN>_USDT (public, live shapes) ----

    @Test
    void readsTheBidOutOfTheBookTicker() {
        Map<String, Object> resp = Map.of("result", true, "data", Map.of("tickers", List.of(
                Map.of("symbol", "BTC_USDT", "bidPrice", "78080.00", "bidSize", "18.689360",
                        "askPrice", "78080.01", "askSize", "7.624851", "timestamp", 1793555221596L))),
                "timestamp", 1793555221973L);

        assertThat(PionexAccountParser.parseBookTicker(resp))
                .isEqualTo(new CoinPrice.Bid(new BigDecimal("78080.00")));
    }

    @Test
    void anInvalidSymbolMeansTheCoinHasNoUsdtPair() {
        Map<String, Object> resp = Map.of("result", false, "code", "MARKET_INVALID_SYMBOL",
                "message", "symbol error", "timestamp", 1793556443L);

        assertThat(PionexAccountParser.parseBookTicker(resp)).isInstanceOf(CoinPrice.NoPair.class);
    }

    @Test
    void anyOtherErrorOrAMalformedTickerIsAFailureNotAMissingPair() {
        assertThatThrownBy(() -> PionexAccountParser.parseBookTicker(Map.of("result", false,
                "code", "SERVER_BUSY", "message", "busy"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PionexAccountParser.parseBookTicker(Map.of("result", true,
                "data", Map.of("tickers", List.of())))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PionexAccountParser.parseBookTicker(Map.of("result", true,
                "data", Map.of("tickers", List.of(Map.of("symbol", "BTC_USDT", "bidPrice", "0"))))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PionexAccountParser.parseBookTicker(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parsesPositionsIntoCoreSymbols() {
        List<ExchangePosition> positions = PionexAccountParser.parsePositions(positionsResponse());

        assertThat(positions).hasSize(2);
        ExchangePosition ada = positions.get(0);
        assertThat(ada.symbol()).isEqualTo("ADAUSDT");
        assertThat(ada.side()).isEqualTo("LONG");
        assertThat(ada.qty()).isEqualByComparingTo("919");
        assertThat(ada.avgPrice()).isEqualByComparingTo("0.2175");
        assertThat(ada.markPrice()).isEqualByComparingTo("0.2211223");
        assertThat(ada.leverage()).isEqualTo(20);
        assertThat(ada.isFlat()).isFalse();
    }

    @Test
    void readsTheCapitalLSpellingOfUnrealizedPnL() {
        // Pionex spells it unrealizedPnL; the first pass looked for unrealizedPnl and got null,
        // which fed a wrong equity into the balance card.
        List<ExchangePosition> positions = PionexAccountParser.parsePositions(positionsResponse());

        assertThat(positions.get(0).unrealizedPnl()).isEqualByComparingTo("3.3288937");
        assertThat(positions.get(1).unrealizedPnl()).isEqualByComparingTo("-1.80752");
    }

    @Test
    void demoPusdPositionsAreDroppedRatherThanCollapsedOntoTheUsdtCoreSymbol() {
        // PR review fix: BTC_PUSD_PERP (paper) and BTC_USDT_PERP (real) both reduce to the core
        // symbol BTCUSDT. If this account ever held both, adoptUntracked matches by core symbol and
        // one would silently overwrite the other's qty/side/entry in the same DB row. This reader
        // reflects the account's real state, so paper contracts are filtered out entirely instead.
        Map<String, Object> resp = Map.of("result", true, "data", Map.of("positions", List.of(
                Map.of("symbol", "BTC_PUSD_PERP", "positionSide", "LONG", "netSize", "1",
                        "avgPrice", "70000", "unrealizedPnL", "0"))));

        assertThat(PionexAccountParser.parsePositions(resp)).isEmpty();
    }

    @Test
    void aRealUsdtPositionIsUnaffectedWhenAPusdTwinAlsoExists() {
        Map<String, Object> resp = Map.of("result", true, "data", Map.of("positions", List.of(
                Map.of("symbol", "BTC_PUSD_PERP", "positionSide", "LONG", "netSize", "1",
                        "avgPrice", "70000", "unrealizedPnL", "0"),
                Map.of("symbol", "BTC_USDT_PERP", "positionSide", "LONG", "netSize", "0.012",
                        "avgPrice", "79264.1", "unrealizedPnL", "4.87"))));

        List<ExchangePosition> positions = PionexAccountParser.parsePositions(resp);

        assertThat(positions).hasSize(1);
        assertThat(positions.get(0).symbol()).isEqualTo("BTCUSDT");
        assertThat(positions.get(0).qty()).isEqualByComparingTo("0.012");
    }

    @Test
    void zeroSizeRowIsFlat() {
        Map<String, Object> resp = Map.of("result", true, "data", Map.of("positions", List.of(
                Map.of("symbol", "ADA_USDT_PERP", "positionSide", "LONG", "netSize", "0",
                        "avgPrice", "0.2", "unrealizedPnL", "0"))));

        assertThat(PionexAccountParser.parsePositions(resp).get(0).isFlat()).isTrue();
    }

    @Test
    void malformedResponsesYieldNoPositionsRatherThanThrowing() {
        assertThat(PionexAccountParser.parsePositions(null)).isEmpty();
        assertThat(PionexAccountParser.parsePositions(Map.of("result", true))).isEmpty();
        assertThat(PionexAccountParser.parsePositions(Map.of("result", true, "data",
                Map.of("positions", List.of(Map.of("noSymbol", "x")))))).isEmpty();
    }
}
