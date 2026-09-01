package com.sonar.trading.exchange.bybit;

import com.sonar.trading.exchange.model.AdapterOrderState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BybitOrderMapperTest {

    // ---- Symbols: Bybit's linear-perp symbol IS the core form, unlike BingX's BTC-USDT ----

    @Test
    void symbolConversionIsIdentityNotDashInsertion() {
        assertThat(BybitOrderMapper.toBybitSymbol("btcusdt")).isEqualTo("BTCUSDT");
        assertThat(BybitOrderMapper.toCoreSymbol("BTCUSDT")).isEqualTo("BTCUSDT");
    }

    @Test
    void toBybitSymbolRejectsNull() {
        assertThatThrownBy(() -> BybitOrderMapper.toBybitSymbol(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- Order state mapping ----

    @Test
    void mapsKnownBybitStatusesToAdapterStates() {
        assertThat(BybitOrderMapper.mapState("New")).isEqualTo(AdapterOrderState.NEW);
        assertThat(BybitOrderMapper.mapState("Untriggered")).isEqualTo(AdapterOrderState.NEW);
        assertThat(BybitOrderMapper.mapState("PartiallyFilled")).isEqualTo(AdapterOrderState.PARTIALLY_FILLED);
        assertThat(BybitOrderMapper.mapState("Filled")).isEqualTo(AdapterOrderState.FILLED);
        assertThat(BybitOrderMapper.mapState("Cancelled")).isEqualTo(AdapterOrderState.CANCELED);
        assertThat(BybitOrderMapper.mapState("Rejected")).isEqualTo(AdapterOrderState.REJECTED);
    }

    @Test
    void nullOrUnknownStatusDefaultsToNew() {
        assertThat(BybitOrderMapper.mapState(null)).isEqualTo(AdapterOrderState.NEW);
        assertThat(BybitOrderMapper.mapState("SomeFutureStatus")).isEqualTo(AdapterOrderState.NEW);
    }

    // ---- Field extraction: top-level, result envelope, result.list[0] fallback ----

    @Test
    void extractsTopLevelField() {
        Map<String, Object> resp = Map.of("orderId", "123");
        assertThat(BybitOrderMapper.extractString(resp, "orderId")).isEqualTo("123");
    }

    @Test
    void extractsFromResultEnvelope() {
        Map<String, Object> resp = Map.of("result", Map.of("orderId", "abc"));
        assertThat(BybitOrderMapper.extractString(resp, "orderId")).isEqualTo("abc");
    }

    @Test
    void extractsFromResultListFirstRow() {
        Map<String, Object> resp = Map.of("result", Map.of("list", List.of(
                Map.of("orderStatus", "Filled", "orderId", "row-0"))));
        assertThat(BybitOrderMapper.extractString(resp, "orderStatus")).isEqualTo("Filled");
        assertThat(BybitOrderMapper.extractString(resp, "orderId")).isEqualTo("row-0");
    }

    @Test
    void extractDecimalParsesNumericStrings() {
        Map<String, Object> resp = Map.of("result", Map.of("closedPnl", "12.5"));
        assertThat(BybitOrderMapper.extractDecimal(resp, "closedPnl")).isEqualByComparingTo(new BigDecimal("12.5"));
    }

    @Test
    void extractDecimalReturnsNullForUnparseable() {
        Map<String, Object> resp = Map.of("foo", "not-a-number");
        assertThat(BybitOrderMapper.extractDecimal(resp, "foo")).isNull();
    }

    @Test
    void extractStringTriesKeysInOrderAndSkipsBlank() {
        Map<String, Object> resp = Map.of("a", "", "b", "value");
        assertThat(BybitOrderMapper.extractString(resp, "a", "b")).isEqualTo("value");
    }

    @Test
    void extractOnNullOrMissingMapReturnsNull() {
        assertThat(BybitOrderMapper.extractString(null, "x")).isNull();
        assertThat(BybitOrderMapper.extractString(Map.of(), "x")).isNull();
    }

    // ---- resultList ----

    @Test
    void resultListReturnsRowsWhenPresent() {
        Map<String, Object> resp = Map.of("result", Map.of("list", List.of(
                Map.of("symbol", "BTCUSDT"), Map.of("symbol", "ETHUSDT"))));
        List<Map<String, Object>> rows = BybitOrderMapper.resultList(resp);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("symbol", "BTCUSDT");
    }

    @Test
    void resultListIsEmptyOnMalformedOrMissingShapes() {
        assertThat(BybitOrderMapper.resultList(null)).isEmpty();
        assertThat(BybitOrderMapper.resultList(Map.of())).isEmpty();
        assertThat(BybitOrderMapper.resultList(Map.of("result", "not-a-map"))).isEmpty();
        assertThat(BybitOrderMapper.resultList(Map.of("result", Map.of("list", "not-a-list")))).isEmpty();
    }
}
