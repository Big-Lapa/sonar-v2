package com.sonar.trading.exchange.binance;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceOrderMapperTest {

    @Test
    void clampClientOrderIdLeavesShortIdsUnchanged() {
        assertThat(BinanceOrderMapper.clampClientOrderId("short-id")).isEqualTo("short-id");
        assertThat(BinanceOrderMapper.clampClientOrderId(null)).isNull();
    }

    @Test
    void clampClientOrderIdKeepsTheLeadingUniquePartNotTheConstantAccountSuffix() {
        // The platform's own id scheme is shortId(proposalId) + "-" + accountId — the unique part
        // is the PREFIX; accountId is constant per account. Two different proposals on the same
        // long-accountId account must not collapse into the same clamped id.
        String proposalA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + "-binance-demo-account";
        String proposalB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" + "-binance-demo-account";

        String clampedA = BinanceOrderMapper.clampClientOrderId(proposalA);
        String clampedB = BinanceOrderMapper.clampClientOrderId(proposalB);

        assertThat(clampedA).hasSize(BinanceOrderMapper.MAX_CLIENT_ORDER_ID);
        assertThat(clampedA).isNotEqualTo(clampedB);
        assertThat(clampedA).startsWith("aaaa");
        assertThat(clampedB).startsWith("bbbb");
    }

    @Test
    void dataRowsReturnsEmptyForNullOrNonArrayResponse() {
        assertThat(BinanceOrderMapper.dataRows(null)).isEmpty();
        assertThat(BinanceOrderMapper.dataRows(java.util.Map.of("code", -1121, "msg", "bad symbol"))).isEmpty();
    }

    @Test
    void dataRowsExtractsMapEntriesFromTheDataArray() {
        Map<String, Object> resp = java.util.Map.of("data", java.util.List.of(
                java.util.Map.of("symbol", "BTCUSDT"), "not-a-map", java.util.Map.of("symbol", "ETHUSDT")));

        var rows = BinanceOrderMapper.dataRows(resp);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("symbol")).containsExactly("BTCUSDT", "ETHUSDT");
    }
}
