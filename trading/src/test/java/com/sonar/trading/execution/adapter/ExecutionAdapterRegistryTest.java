package com.sonar.trading.execution.adapter;

import com.sonar.trading.exchange.spi.ExecutionAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Multi-venue routing (grilled plan): registry resolves per-venue adapters by name. */
class ExecutionAdapterRegistryTest {

    private static ExecutionAdapter adapterNamed(String name) {
        ExecutionAdapter adapter = Mockito.mock(ExecutionAdapter.class);
        when(adapter.adapterName()).thenReturn(name);
        return adapter;
    }

    @Test
    void resolvesByExchangeNameCaseInsensitively() {
        ExecutionAdapter bingx = adapterNamed("BINGX");
        ExecutionAdapter bybit = adapterNamed("BYBIT");
        ExecutionAdapterRegistry registry = new ExecutionAdapterRegistry(List.of(bingx, bybit));

        assertThat(registry.forExchange("bingx")).contains(bingx);
        assertThat(registry.forExchange("BingX")).contains(bingx);
        assertThat(registry.forExchange("bybit")).contains(bybit);
    }

    @Test
    void unknownOrNullExchangeIsEmpty() {
        ExecutionAdapterRegistry registry = new ExecutionAdapterRegistry(List.of(adapterNamed("BINGX")));

        assertThat(registry.forExchange("binance")).isEmpty();
        assertThat(registry.forExchange(null)).isEmpty();
    }

    @Test
    void emptyRegistryResolvesNothing_paperModeSafe() {
        ExecutionAdapterRegistry registry = new ExecutionAdapterRegistry(List.of());

        assertThat(registry.forExchange("bingx")).isEmpty();
        assertThat(registry.any()).isEmpty();
    }

    @Test
    void anyReturnsSomeWiredAdapterWhenAtLeastOneExists() {
        ExecutionAdapterRegistry registry = new ExecutionAdapterRegistry(List.of(adapterNamed("BYBIT")));

        assertThat(registry.any()).isPresent();
    }
}
