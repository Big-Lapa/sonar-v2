package com.sonar.trading.execution;

import com.sonar.trading.exchange.dto.AdapterOpenOrder;
import com.sonar.trading.exchange.dto.ExchangeError;
import com.sonar.trading.exchange.exception.ExchangeException;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-account since credentials moved off the venue: StopGuard resolves whichever
 * {@link ExecutionAdapter} the ACCOUNT is wired to via {@link ExecutionAdapterRegistry}.
 *
 * <p>The central rule under test is that "could not ask the venue" and "the position has no stop"
 * are different answers. Collapsing them once turned a transient BingX rate limit into
 * MISSING_STOP alerts on protected positions — and would have driven remediation to place a
 * duplicate stop on a real-money account had the endpoint not been banned outright.
 */
class StopGuardTest {

    private static final BigDecimal QTY = new BigDecimal("0.1");
    private static final BigDecimal SL = new BigDecimal("60000");

    private static StopGuard stopGuardWith(String accountId, ExecutionAdapter adapter) {
        ExecutionAdapterRegistry registry = Mockito.mock(ExecutionAdapterRegistry.class);
        when(registry.forAccount(accountId)).thenReturn(Optional.ofNullable(adapter));
        return new StopGuard(registry);
    }

    private static AdapterOpenOrder order(String type, String trigger) {
        return new AdapterOpenOrder("id-" + type, null, "BTCUSDT", "SELL", type,
                QTY, BigDecimal.ZERO, trigger == null ? null : new BigDecimal(trigger), "NEW");
    }

    // ---- inspect() ----

    @Test
    void inspectReadsStopAndTakeProfitLevelsFromOneCall() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.listOpenOrders("BTCUSDT")).thenReturn(List.of(
                order("STOP_MARKET", "75000"), order("TAKE_PROFIT_MARKET", "81000")));

        StopGuard.StopInspection stops = stopGuardWith("bingx-prod", adapter).inspect("bingx-prod", "BTCUSDT");

        assertThat(stops.readable()).isTrue();
        assertThat(stops.hasStop()).isTrue();
        assertThat(stops.stopLoss()).isEqualByComparingTo("75000");
        assertThat(stops.takeProfit()).isEqualByComparingTo("81000");
        verify(adapter).listOpenOrders("BTCUSDT");
    }

    @Test
    void inspectIsUnreadableWhenTheVenueCallFails_notAnAbsentStop() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.listOpenOrders("BTCUSDT"))
                .thenThrow(new ExchangeException(new ExchangeError("100410", "rate limited")));

        StopGuard.StopInspection stops = stopGuardWith("bingx-prod", adapter).inspect("bingx-prod", "BTCUSDT");

        assertThat(stops.readable()).isFalse();
        assertThat(stops.hasStop()).isFalse();
    }

    @Test
    void inspectIsUnreadableWhenNoAdapterWired() {
        StopGuard.StopInspection stops = stopGuardWith("pionex-prod", null).inspect("pionex-prod", "BTCUSDT");

        assertThat(stops.readable()).isFalse();
    }

    // ---- verifyAndRepair() ----

    @Test
    void trueWithoutRemediationWhenStopAlreadyOpen() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        StopGuard stopGuard = stopGuardWith("bybit-demo", adapter);
        var known = new StopGuard.StopInspection(true, true, SL, null);

        assertThat(stopGuard.verifyAndRepair("bybit-demo", "BTCUSDT", QTY, SL, known)).isTrue();
        verify(adapter, never()).placeReduceOnlyStop(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void unreadableInspectionNeitherAlertsNorPlacesAnOrder() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        StopGuard stopGuard = stopGuardWith("bingx-prod", adapter);

        boolean result = stopGuard.verifyAndRepair("bingx-prod", "BTCUSDT", QTY, SL,
                StopGuard.StopInspection.unreadable());

        assertThat(result).isTrue(); // "unknown" must not raise MISSING_STOP
        verify(adapter, never()).placeReduceOnlyStop(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void placesRemediationStopWhenGenuinelyMissingThenRechecks() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.listOpenOrders("BTCUSDT")).thenReturn(List.of(order("STOP_MARKET", "60000")));
        StopGuard stopGuard = stopGuardWith("bingx-vst", adapter);
        var known = new StopGuard.StopInspection(true, false, null, null);

        boolean result = stopGuard.verifyAndRepair("bingx-vst", "BTCUSDT", QTY, SL, known);

        assertThat(result).isTrue();
        verify(adapter).placeReduceOnlyStop("BTCUSDT", QTY, SL);
    }

    @Test
    void falseWhenRemediationStillFindsNoStop() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.listOpenOrders("BTCUSDT")).thenReturn(List.of());
        StopGuard stopGuard = stopGuardWith("bingx-vst", adapter);

        boolean result = stopGuard.verifyAndRepair("bingx-vst", "BTCUSDT", QTY, SL,
                new StopGuard.StopInspection(true, false, null, null));

        assertThat(result).isFalse();
    }

    @Test
    void falseWhenPlaceReduceOnlyStopThrows() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        Mockito.doThrow(new ExchangeException(new ExchangeError("IO_ERROR", "timeout")))
                .when(adapter).placeReduceOnlyStop(Mockito.any(), Mockito.any(), Mockito.any());
        StopGuard stopGuard = stopGuardWith("bingx-vst", adapter);

        boolean result = stopGuard.verifyAndRepair("bingx-vst", "BTCUSDT", QTY, SL,
                new StopGuard.StopInspection(true, false, null, null));

        assertThat(result).isFalse();
    }

    @Test
    void neverPlacesAnOrderWithoutAKnownStopLevel() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        StopGuard stopGuard = stopGuardWith("bingx-prod", adapter);

        boolean result = stopGuard.verifyAndRepair("bingx-prod", "BTCUSDT", QTY, null,
                new StopGuard.StopInspection(true, false, null, null));

        assertThat(result).isFalse();
        verify(adapter, never()).placeReduceOnlyStop(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void hasStopOrderDelegatesToAdapterAndIsFalseWhenUnresolved() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.hasOpenStopOrder("ETHUSDT")).thenReturn(true);

        assertThat(stopGuardWith("bybit-demo", adapter).hasStopOrder("bybit-demo", "ETHUSDT")).isTrue();
        assertThat(stopGuardWith("bybit-demo", null).hasStopOrder("bybit-demo", "ETHUSDT")).isFalse();
    }
}
