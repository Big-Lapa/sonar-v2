package com.sonar.trading.health;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exports {@link SystemHealthService} state as Micrometer gauges for the Grafana platform-health
 * dashboard. SystemHealth is tracked locally per service (ADR-0008, phase A) and until now was
 * internal-only — visible to the risk health gate but not to operators. These gauges expose the
 * same state read-only, without changing health semantics.
 *
 * <p>Fixed gauges (lag, reconciliation flag, out-of-sync count) are registered once in the
 * constructor. Per-symbol staleness and per-account circuit gauges are registered lazily by a
 * scheduled sweep, because symbols appear dynamically as market events arrive and accounts as
 * their gateways report circuit state (multi-account epic — one circuit per account, so the gauge
 * carries an {@code account} tag; the tagged and an untagged variant of the same name cannot
 * coexist in the Prometheus registry, hence no legacy untagged gauge). A symbol returned by
 * {@link SystemHealthService#trackedSymbols()} always has a last-event timestamp, so the staleness
 * gauge never reports the {@code Long.MAX_VALUE} "unknown symbol" sentinel.
 */
@Component
public class SystemHealthMetrics {

    private final SystemHealthService health;
    private final MeterRegistry registry;
    private final Set<String> registeredSymbols = ConcurrentHashMap.newKeySet();
    private final Set<String> registeredCircuitAccounts = ConcurrentHashMap.newKeySet();

    public SystemHealthMetrics(SystemHealthService health, MeterRegistry registry) {
        this.health = health;
        this.registry = registry;
        Gauge.builder("system_health_consumer_lag", health, SystemHealthService::getConsumerLag)
                .description("Kafka consumer lag as reported to SystemHealthService")
                .register(registry);
        Gauge.builder("system_health_reconciliation_in_sync", health,
                        h -> h.isReconciliationInSync() ? 1 : 0)
                .description("1 when local state matches the exchange, 0 during a mismatch")
                .register(registry);
        Gauge.builder("system_health_symbols_out_of_sync", health, SystemHealthService::outOfSyncCount)
                .description("Number of (account, symbol) pairs in a reconciliation safe-window (T17)")
                .register(registry);
        // Always expose the default account so single-account / PAPER setups keep a 0-valued
        // series on the dashboard instead of "no data".
        registerCircuitGauge(SystemHealthService.DEFAULT_ACCOUNT);
        // Gateways constructed before this component have already reported their circuit state.
        registerDynamicGauges();
    }

    /** Registers gauges for newly seen symbols / circuit accounts; already registered ones are skipped. */
    @Scheduled(fixedDelay = 15_000)
    void registerDynamicGauges() {
        for (String symbol : health.trackedSymbols()) {
            if (registeredSymbols.add(symbol)) {
                Gauge.builder("system_health_market_data_staleness_ms", health,
                                h -> h.getStalenessMs(symbol))
                        .tag("symbol", symbol)
                        .description("Milliseconds since the last market event for this symbol")
                        .register(registry);
            }
        }
        for (String account : health.circuitAccounts()) {
            registerCircuitGauge(account);
        }
    }

    private void registerCircuitGauge(String account) {
        if (registeredCircuitAccounts.add(account)) {
            Gauge.builder("system_health_execution_circuit_open", health,
                            h -> h.isExecutionCircuitOpen(account) ? 1 : 0)
                    .tag("account", account)
                    .description("1 when this account's execution circuit breaker is open (T16/P3)")
                    .register(registry);
        }
    }
}
