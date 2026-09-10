package com.sonar.trading.reconciliation;

import com.sonar.trading.exchange.binance.BinanceFuturesClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

class BinanceAccountReaderTest {

    @Test
    void sumIncomeSinceAddsUpIncomeFieldsAcrossRows() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        when(client.getIncome(any(), anyLong(), anyLong(), any())).thenReturn(Map.of("data", List.of(
                Map.of("income", "1.5"), Map.of("income", "-0.5"))));

        BigDecimal sum = new BinanceAccountReader(client).sumIncomeSince(Instant.EPOCH);

        assertThat(sum).isEqualByComparingTo("1.0");
    }

    @Test
    void sumIncomeSinceReturnsZeroForAGenuinelyEmptyPeriod() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        when(client.getIncome(any(), anyLong(), anyLong(), any())).thenReturn(Map.of("data", List.of()));

        BigDecimal sum = new BinanceAccountReader(client).sumIncomeSince(Instant.EPOCH);

        assertThat(sum).isEqualByComparingTo("0");
    }

    @Test
    void sumIncomeSinceReturnsNullRatherThanZeroWhenTheResponseIsABusinessErrorNotAnArray() {
        // Binance business errors ({"code":-1121,...}) come back as HTTP 200 with an object body,
        // not the array this endpoint returns on success — must not be read the same as "0 rows".
        // AccountReader.sumIncomeSince's contract: null means "keep the previous value", so a
        // caller reading 0 here would zero out today's realized_pnl instead of preserving it.
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        when(client.getIncome(any(), anyLong(), anyLong(), any()))
                .thenReturn(Map.of("code", -1121, "msg", "Invalid symbol."));

        BigDecimal sum = new BinanceAccountReader(client).sumIncomeSince(Instant.EPOCH);

        assertThat(sum).isNull();
    }

    @Test
    void sumIncomeSinceReturnsNullWhenTheClientThrows() {
        BinanceFuturesClient client = Mockito.mock(BinanceFuturesClient.class);
        when(client.getIncome(any(), anyLong(), anyLong(), any())).thenThrow(new RuntimeException("timeout"));

        BigDecimal sum = new BinanceAccountReader(client).sumIncomeSince(Instant.EPOCH);

        assertThat(sum).isNull();
    }
}
