package com.sonar.trading.execution.adapter;

import com.sonar.trading.exchange.spi.ExecutionAdapter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the {@link ExecutionAdapter} for an account's venue (full multi-venue routing —
 * grilled plan). Built from every {@code ExecutionAdapter} bean present, keyed by
 * {@link ExecutionAdapter#adapterName()} — so PAPER mode (no adapters wired at all) yields an
 * empty registry rather than a startup error, same "LIVE-only" posture as the gateways.
 */
@Component
public class ExecutionAdapterRegistry {

    private final Map<String, ExecutionAdapter> byExchange = new ConcurrentHashMap<>();

    public ExecutionAdapterRegistry(List<ExecutionAdapter> adapters) {
        for (ExecutionAdapter adapter : adapters) {
            byExchange.put(adapter.adapterName().toUpperCase(Locale.ROOT), adapter);
        }
    }

    /** {@code exchange} is case-insensitive (account registry stores it lowercase, e.g. "bingx"). */
    public Optional<ExecutionAdapter> forExchange(String exchange) {
        if (exchange == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byExchange.get(exchange.toUpperCase(Locale.ROOT)));
    }

    /**
     * Any wired adapter, deterministic-ish but venue-unspecified — for checks that are venue
     * -indifferent (e.g. a coarse cross-venue price-drift sanity gate before an account/venue is
     * even known yet, see {@code ProposalExecutionService.checkPriceDrift}). Never use this for
     * anything that mutates state.
     */
    public Optional<ExecutionAdapter> any() {
        return byExchange.values().stream().findFirst();
    }
}
