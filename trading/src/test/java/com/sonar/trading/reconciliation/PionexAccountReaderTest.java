package com.sonar.trading.reconciliation;

import com.sonar.trading.exchange.pionex.PionexFuturesClient;
import com.sonar.trading.reconciliation.model.ExchangeBalance;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Equity of a Pionex account is the whole futures wallet valued in USDT (every coin except the PUSD
 * paper pool, non-stable coins at the public book-ticker bid) plus open positions' unrealized PnL.
 */
class PionexAccountReaderTest {

    private static Map<String, Object> wallet(Map<String, Object>... rows) {
        return Map.of("result", true, "data", Map.of("balances", List.of(rows)));
    }

    private static Map<String, Object> row(String coin, String free) {
        return Map.of("coin", coin, "free", free, "frozen", "0", "debts", "0");
    }

    private static Map<String, Object> bid(String symbol, String price) {
        return Map.of("result", true, "data", Map.of("tickers", List.of(
                Map.of("symbol", symbol, "bidPrice", price, "askPrice", price))));
    }

    private static Map<String, Object> noPositions() {
        return Map.of("result", true, "data", Map.of("positions", List.of()));
    }

    private static Map<String, Object> positionWithPnl(String pnl) {
        return Map.of("result", true, "data", Map.of("positions", List.of(
                Map.of("symbol", "ADA_USDT_PERP", "positionSide", "LONG", "netSize", "919",
                        "avgPrice", "0.2175", "markPrice", "0.2211223", "unrealizedPnL", pnl))));
    }

    @Test
    void balanceIsTheWholeWalletInUsdtAndAvailableIsTheFreeUsdtOnly() {
        // The live wallet today: USDT 0, the money sits in BTC, plus the PUSD paper pool.
        PionexFuturesClient client = Mockito.mock(PionexFuturesClient.class);
        when(client.getBalances()).thenReturn(wallet(row("BTC", "0.003"), row("USDT", "0"),
                row("PUSD", "499988.6568433")));
        when(client.getBookTicker("BTC_USDT")).thenReturn(bid("BTC_USDT", "78000"));
        when(client.getPositions(any())).thenReturn(noPositions());

        ExchangeBalance b = new PionexAccountReader(client).getBalance().orElseThrow();

        assertThat(b.asset()).isEqualTo("USDT");
        assertThat(b.balance()).isEqualByComparingTo("234");
        assertThat(b.equity()).isEqualByComparingTo("234");
        assertThat(b.available()).isEqualByComparingTo("0");
    }

    @Test
    void equityAddsOpenPositionsUnrealizedPnlOnTopOfTheWalletValue() {
        PionexFuturesClient client = Mockito.mock(PionexFuturesClient.class);
        when(client.getBalances()).thenReturn(wallet(row("BTC", "0.003"), row("USDT", "100")));
        when(client.getBookTicker("BTC_USDT")).thenReturn(bid("BTC_USDT", "78000"));
        when(client.getPositions(any())).thenReturn(positionWithPnl("3.5"));

        ExchangeBalance b = new PionexAccountReader(client).getBalance().orElseThrow();

        assertThat(b.balance()).isEqualByComparingTo("334");
        assertThat(b.unrealizedPnl()).isEqualByComparingTo("3.5");
        assertThat(b.equity()).isEqualByComparingTo("337.5");
        assertThat(b.available()).isEqualByComparingTo("100");
    }

    @Test
    void stablecoinsAndZeroRowsNeverHitTheTicker() {
        PionexFuturesClient client = Mockito.mock(PionexFuturesClient.class);
        when(client.getBalances()).thenReturn(wallet(row("USDT", "50"), row("USDC", "5"), row("ETH", "0")));
        when(client.getPositions(any())).thenReturn(noPositions());

        ExchangeBalance b = new PionexAccountReader(client).getBalance().orElseThrow();

        assertThat(b.balance()).isEqualByComparingTo("55");
        verify(client, never()).getBookTicker(anyString());
    }

    @Test
    void aCoinWithNoUsdtPairIsLeftOutOfTheBalance() {
        PionexFuturesClient client = Mockito.mock(PionexFuturesClient.class);
        when(client.getBalances()).thenReturn(wallet(row("BTC", "0.003"), row("DUSTCOIN", "12.5")));
        when(client.getBookTicker("BTC_USDT")).thenReturn(bid("BTC_USDT", "78000"));
        when(client.getBookTicker("DUSTCOIN_USDT")).thenReturn(Map.of("result", false,
                "code", "MARKET_INVALID_SYMBOL", "message", "symbol error"));
        when(client.getPositions(any())).thenReturn(noPositions());

        ExchangeBalance b = new PionexAccountReader(client).getBalance().orElseThrow();

        assertThat(b.balance()).isEqualByComparingTo("234");
    }

    @Test
    void aTickerCallThatFailsMakesTheBalanceUnknown() {
        // Thrown, not a lowered number: MinutePoller then skips the tick and leaves daily_baseline alone.
        PionexFuturesClient client = Mockito.mock(PionexFuturesClient.class);
        when(client.getBalances()).thenReturn(wallet(row("BTC", "0.003"), row("USDT", "10")));
        when(client.getBookTicker("BTC_USDT")).thenThrow(new RuntimeException("timeout"));
        when(client.getPositions(any())).thenReturn(noPositions());

        assertThatThrownBy(() -> new PionexAccountReader(client).getBalance())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aTickerErrorOtherThanAMissingPairAlsoMakesTheBalanceUnknown() {
        PionexFuturesClient client = Mockito.mock(PionexFuturesClient.class);
        when(client.getBalances()).thenReturn(wallet(row("BTC", "0.003")));
        when(client.getBookTicker("BTC_USDT")).thenReturn(Map.of("result", false,
                "code", "SERVER_BUSY", "message", "busy"));
        when(client.getPositions(any())).thenReturn(noPositions());

        assertThatThrownBy(() -> new PionexAccountReader(client).getBalance())
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void whenThePositionsCallFailsEquityFallsBackToTheWalletValue() {
        PionexFuturesClient client = Mockito.mock(PionexFuturesClient.class);
        when(client.getBalances()).thenReturn(wallet(row("BTC", "0.003")));
        when(client.getBookTicker("BTC_USDT")).thenReturn(bid("BTC_USDT", "78000"));
        when(client.getPositions(any())).thenThrow(new RuntimeException("positions down"));

        ExchangeBalance b = new PionexAccountReader(client).getBalance().orElseThrow();

        assertThat(b.equity()).isEqualByComparingTo("234");
        assertThat(b.unrealizedPnl()).isNull();
    }

    @Test
    void anEmptyWalletYieldsNoBalance() {
        PionexFuturesClient client = Mockito.mock(PionexFuturesClient.class);
        when(client.getBalances()).thenReturn(wallet());

        assertThat(new PionexAccountReader(client).getBalance()).isEmpty();
    }
}
