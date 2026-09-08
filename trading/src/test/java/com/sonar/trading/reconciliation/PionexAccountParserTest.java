package com.sonar.trading.reconciliation;

import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangePosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void parsesTheRequestedAssetOutOfTheCoinWallet() {
        Optional<ExchangeBalance> balance = PionexAccountParser.parseBalance(balancesResponse(), "USDT");

        assertThat(balance).isPresent();
        assertThat(balance.get().asset()).isEqualTo("USDT");
        assertThat(balance.get().balance()).isEqualByComparingTo("135.75090540");
        assertThat(balance.get().available()).isEqualByComparingTo("135.75090540");
    }

    @Test
    void fallsBackToTheFirstRowWhenTheRequestedAssetIsAbsent() {
        // The account also holds a separate PUSD paper-trading pool; an unknown requested asset
        // must not yield "no balance at all" when the venue clearly returned rows.
        Optional<ExchangeBalance> balance = PionexAccountParser.parseBalance(balancesResponse(), "EUR");

        assertThat(balance).isPresent();
        assertThat(balance.get().asset()).isEqualTo("BTC");
    }

    @Test
    void emptyResponseYieldsNoBalance() {
        assertThat(PionexAccountParser.parseBalance(Map.of("result", true, "data",
                Map.of("balances", List.of())), "USDT")).isEmpty();
        assertThat(PionexAccountParser.parseBalance(null, "USDT")).isEmpty();
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
    void demoPusdPairsCollapseOntoTheSameCoreSymbolAsTheirUsdtTwin() {
        Map<String, Object> resp = Map.of("result", true, "data", Map.of("positions", List.of(
                Map.of("symbol", "BTC_PUSD_PERP", "positionSide", "LONG", "netSize", "1",
                        "avgPrice", "70000", "unrealizedPnL", "0"))));

        assertThat(PionexAccountParser.parsePositions(resp).get(0).symbol()).isEqualTo("BTCUSDT");
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
