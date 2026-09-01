package com.sonar.trading.execution;

import com.sonar.trading.exchange.exception.ExchangeException;
import com.sonar.trading.exchange.dto.ExchangeError;
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
 * Venue-agnostic since the grilled Bybit-integration plan (Q5): StopGuard no longer talks to a
 * BingX-specific client — it resolves whatever {@link ExecutionAdapter} the account's venue is
 * wired to via {@link ExecutionAdapterRegistry}.
 */
class StopGuardTest {

    private static StopGuard stopGuardWith(String exchange, ExecutionAdapter adapter) {
        ExecutionAdapterRegistry registry = Mockito.mock(ExecutionAdapterRegistry.class);
        when(registry.forExchange(exchange)).thenReturn(Optional.ofNullable(adapter));
        return new StopGuard(registry);
    }

    @Test
    void trueWithoutRemediationWhenStopAlreadyOpen() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.hasOpenStopOrder("BTCUSDT")).thenReturn(true);
        StopGuard stopGuard = stopGuardWith("bybit", adapter);

        boolean result = stopGuard.verifyAndRepair("bybit", "BTCUSDT", new BigDecimal("0.1"), new BigDecimal("60000"));

        assertThat(result).isTrue();
        verify(adapter, never()).placeReduceOnlyStop(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void placesRemediationStopWhenMissingThenRechecks() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.hasOpenStopOrder("BTCUSDT")).thenReturn(false, true); // missing, then present after remediation
        StopGuard stopGuard = stopGuardWith("bingx", adapter);

        boolean result = stopGuard.verifyAndRepair("bingx", "BTCUSDT", new BigDecimal("0.1"), new BigDecimal("60000"));

        assertThat(result).isTrue();
        verify(adapter).placeReduceOnlyStop("BTCUSDT", new BigDecimal("0.1"), new BigDecimal("60000"));
    }

    @Test
    void falseWhenRemediationStillFindsNoStop() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.hasOpenStopOrder("BTCUSDT")).thenReturn(false, false);
        StopGuard stopGuard = stopGuardWith("bingx", adapter);

        boolean result = stopGuard.verifyAndRepair("bingx", "BTCUSDT", new BigDecimal("0.1"), new BigDecimal("60000"));

        assertThat(result).isFalse();
    }

    @Test
    void falseWhenPlaceReduceOnlyStopThrows() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.hasOpenStopOrder("BTCUSDT")).thenReturn(false);
        Mockito.doThrow(new ExchangeException(new ExchangeError("IO_ERROR", "timeout")))
                .when(adapter).placeReduceOnlyStop(Mockito.any(), Mockito.any(), Mockito.any());
        StopGuard stopGuard = stopGuardWith("bingx", adapter);

        boolean result = stopGuard.verifyAndRepair("bingx", "BTCUSDT", new BigDecimal("0.1"), new BigDecimal("60000"));

        assertThat(result).isFalse();
    }

    @Test
    void falseWhenNoAdapterWiredForVenue_neverThrows() {
        StopGuard stopGuard = stopGuardWith("bybit", null);

        boolean result = stopGuard.verifyAndRepair("bybit", "BTCUSDT", new BigDecimal("0.1"), new BigDecimal("60000"));

        assertThat(result).isFalse();
    }

    @Test
    void hasStopOrderDelegatesToAdapterAndIsFalseWhenUnresolved() {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.hasOpenStopOrder("ETHUSDT")).thenReturn(true);
        StopGuard withAdapter = stopGuardWith("bybit", adapter);
        StopGuard withoutAdapter = stopGuardWith("bybit", null);

        assertThat(withAdapter.hasStopOrder("bybit", "ETHUSDT")).isTrue();
        assertThat(withoutAdapter.hasStopOrder("bybit", "ETHUSDT")).isFalse();
    }
}
