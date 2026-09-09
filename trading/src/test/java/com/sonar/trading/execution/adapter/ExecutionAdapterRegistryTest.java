package com.sonar.trading.execution.adapter;

import com.sonar.trading.exchange.spi.ExecutionAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-account routing: the registry resolves an adapter by ACCOUNT id, not venue — two accounts on
 * the same exchange (BingX VST demo and BingX production) each get their own client, so a lookup
 * can never hand a real-money order the demo adapter.
 */
class ExecutionAdapterRegistryTest {

    private static ExecutionAdapter adapter() {
        return Mockito.mock(ExecutionAdapter.class);
    }

    @Test
    void resolvesByAccountIdCaseInsensitively() {
        ExecutionAdapter vst = adapter();
        ExecutionAdapter prod = adapter();
        ExecutionAdapterRegistry registry = new ExecutionAdapterRegistry(
                Map.of("bingx-vst", vst, "bingx-prod", prod));

        assertThat(registry.forAccount("bingx-vst")).contains(vst);
        assertThat(registry.forAccount("BingX-VST")).contains(vst);
        assertThat(registry.forAccount("bingx-prod")).contains(prod);
    }

    @Test
    void twoAccountsOnTheSameVenueStayDistinct() {
        ExecutionAdapter vst = adapter();
        ExecutionAdapter prod = adapter();
        ExecutionAdapterRegistry registry = new ExecutionAdapterRegistry(
                Map.of("bingx-vst", vst, "bingx-prod", prod));

        assertThat(registry.forAccount("bingx-vst")).contains(vst);
        assertThat(registry.forAccount("bingx-prod")).contains(prod);
    }

    @Test
    void unknownOrNullAccountIsEmpty() {
        ExecutionAdapterRegistry registry = new ExecutionAdapterRegistry(Map.of("bingx-vst", adapter()));

        assertThat(registry.forAccount("pionex-demo")).isEmpty();
        assertThat(registry.forAccount(null)).isEmpty();
    }

    @Test
    void emptyRegistryResolvesNothing_paperModeSafe() {
        ExecutionAdapterRegistry registry = new ExecutionAdapterRegistry(Map.of());

        assertThat(registry.forAccount("bingx-vst")).isEmpty();
        assertThat(registry.any()).isEmpty();
    }

    @Test
    void anyReturnsSomeWiredAdapterWhenAtLeastOneExists() {
        ExecutionAdapterRegistry registry = new ExecutionAdapterRegistry(Map.of("bybit-demo", adapter()));

        assertThat(registry.any()).isPresent();
    }
}
