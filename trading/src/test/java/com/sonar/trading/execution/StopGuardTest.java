package com.sonar.trading.execution;

import com.sonar.trading.exchange.dto.ExchangeError;
import com.sonar.trading.exchange.dto.StopInspection;
import com.sonar.trading.exchange.exception.ExchangeException;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-account since credentials moved off the venue: StopGuard resolves whichever
 * {@link ExecutionAdapter} the ACCOUNT is wired to via {@link ExecutionAdapterRegistry}, then reads
 * that adapter's {@link ExecutionAdapter#inspectStops}. StopGuard itself is deliberately
 * adapter-agnostic about HOW a stop is recognised — that used to be a shared type-string scan here,
 * which worked for BingX (a separate STOP_MARKET order) and silently misread Bybit (a position-level
 * field, with its own conditional orders splitting the type across two response fields). That
 * classification now lives in each adapter (covered per-venue in
 * {@code BybitExecutionAdapterTest}/{@code LiveExecutionAdapter}'s own tests); what belongs here is
 * the venue-agnostic contract: "could not ask" is not "unprotected".
 */
class StopGuardTest {

    private static final BigDecimal QTY = new BigDecimal("0.1");
    private static final BigDecimal SL = new BigDecimal("60000");

    private static StopGuard stopGuardWith(String accountId, ExecutionAdapter adapter) {
        ExecutionAdapterRegistry registry = Mockito.mock(ExecutionAdapterRegistry.class);
        when(registry.forAccount(accountId)).thenReturn(Optional.ofNullable(adapter));
        return new StopGuard(registry);
    }

    // ---- inspect() ----

    @Test
    void inspectDelegatesToTheAccountsAdapter() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.inspectStops("BTCUSDT")).thenReturn(new StopInspection(true, SL, new BigDecimal("70000")));

        StopGuard.Check check = stopGuardWith("bingx-prod", adapter).inspect("bingx-prod", "BTCUSDT");

        assertThat(check.readable()).isTrue();
        assertThat(check.hasStop()).isTrue();
        assertThat(check.stopLoss()).isEqualByComparingTo(SL);
        assertThat(check.takeProfit()).isEqualByComparingTo("70000");
    }

    @Test
    void inspectIsUnreadableWhenTheAdapterCallFails_notAnAbsentStop() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.inspectStops("BTCUSDT"))
                .thenThrow(new ExchangeException(new ExchangeError("100410", "rate limited")));

        StopGuard.Check check = stopGuardWith("bingx-prod", adapter).inspect("bingx-prod", "BTCUSDT");

        assertThat(check.readable()).isFalse();
        assertThat(check.hasStop()).isFalse();
    }

    @Test
    void inspectIsUnreadableWhenNoAdapterWired() {
        StopGuard.Check check = stopGuardWith("pionex-prod", null).inspect("pionex-prod", "BTCUSDT");

        assertThat(check.readable()).isFalse();
    }

    // ---- verifyAndRepair() ----

    @Test
    void trueWithoutRemediationWhenStopAlreadyOpen() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        StopGuard stopGuard = stopGuardWith("bybit-demo", adapter);
        var known = new StopGuard.Check(true, true, SL, null);

        assertThat(stopGuard.verifyAndRepair("bybit-demo", "BTCUSDT", QTY, SL, known)).isTrue();
        verify(adapter, never()).placeReduceOnlyStop(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void unreadableInspectionNeitherAlertsNorPlacesAnOrder() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        StopGuard stopGuard = stopGuardWith("bingx-prod", adapter);

        boolean result = stopGuard.verifyAndRepair("bingx-prod", "BTCUSDT", QTY, SL, StopGuard.Check.unreadable());

        assertThat(result).isTrue(); // "unknown" must not raise MISSING_STOP
        verify(adapter, never()).placeReduceOnlyStop(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void placesRemediationStopWhenGenuinelyMissingThenRechecks() {
        // `known` (passed in below) is what the caller already learned from an EARLIER inspect()
        // call — verifyAndRepair itself calls inspectStops() only once, to recheck AFTER placing
        // the order, so the mock needs exactly one (successful) stubbed answer here.
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.inspectStops("BTCUSDT")).thenReturn(new StopInspection(true, SL, null));
        StopGuard stopGuard = stopGuardWith("bingx-vst", adapter);
        var known = new StopGuard.Check(true, false, null, null);

        boolean result = stopGuard.verifyAndRepair("bingx-vst", "BTCUSDT", QTY, SL, known);

        assertThat(result).isTrue();
        verify(adapter).placeReduceOnlyStop("BTCUSDT", QTY, SL);
    }

    @Test
    void falseWhenRemediationStillFindsNoStop() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.inspectStops("BTCUSDT")).thenReturn(StopInspection.NONE);
        StopGuard stopGuard = stopGuardWith("bingx-vst", adapter);

        boolean result = stopGuard.verifyAndRepair("bingx-vst", "BTCUSDT", QTY, SL,
                new StopGuard.Check(true, false, null, null));

        assertThat(result).isFalse();
    }

    @Test
    void falseWhenPlaceReduceOnlyStopThrows() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        Mockito.doThrow(new ExchangeException(new ExchangeError("IO_ERROR", "timeout")))
                .when(adapter).placeReduceOnlyStop(Mockito.any(), Mockito.any(), Mockito.any());
        StopGuard stopGuard = stopGuardWith("bingx-vst", adapter);

        boolean result = stopGuard.verifyAndRepair("bingx-vst", "BTCUSDT", QTY, SL,
                new StopGuard.Check(true, false, null, null));

        assertThat(result).isFalse();
    }

    @Test
    void neverPlacesAnOrderWithoutAKnownStopLevel() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        StopGuard stopGuard = stopGuardWith("bingx-prod", adapter);

        boolean result = stopGuard.verifyAndRepair("bingx-prod", "BTCUSDT", QTY, null,
                new StopGuard.Check(true, false, null, null));

        assertThat(result).isFalse();
        verify(adapter, never()).placeReduceOnlyStop(Mockito.any(), Mockito.any(), Mockito.any());
    }

    // ---- hasStopOrder() ----

    @Test
    void hasStopOrderReflectsAReadableInspection() {
        ExecutionAdapter present = Mockito.mock(ExecutionAdapter.class);
        when(present.inspectStops("ETHUSDT")).thenReturn(new StopInspection(true, SL, null));
        ExecutionAdapter absent = Mockito.mock(ExecutionAdapter.class);
        when(absent.inspectStops("ETHUSDT")).thenReturn(StopInspection.NONE);

        assertThat(stopGuardWith("bybit-demo", present).hasStopOrder("bybit-demo", "ETHUSDT")).isTrue();
        assertThat(stopGuardWith("bybit-demo", absent).hasStopOrder("bybit-demo", "ETHUSDT")).isFalse();
    }

    @Test
    void hasStopOrderDoesNotAlarmOnAnUnreadableInspection() {
        // The bug this closes: PendingLimitOrderReconciler and ProposalExecutionService's add-on
        // branches call hasStopOrder for a MISSING_STOP decision. The old implementation called
        // adapter.hasOpenStopOrder directly and folded "no adapter" AND any exception into false —
        // the exact "unknown looks unprotected" defect verifyAndRepair was fixed for, just reachable
        // from a different caller.
        ExecutionAdapter throwing = Mockito.mock(ExecutionAdapter.class);
        when(throwing.inspectStops("ETHUSDT"))
                .thenThrow(new ExchangeException(new ExchangeError("100410", "rate limited")));

        assertThat(stopGuardWith("bingx-prod", throwing).hasStopOrder("bingx-prod", "ETHUSDT")).isTrue();
        assertThat(stopGuardWith("pionex-prod", null).hasStopOrder("pionex-prod", "ETHUSDT")).isTrue();
    }
}
