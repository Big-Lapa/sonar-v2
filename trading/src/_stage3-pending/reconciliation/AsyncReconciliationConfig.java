package com.sonar.trading.reconciliation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Executor for the post-order reconcile fast path ({@link ReconciliationWorker#onOrderSettled}).
 *
 * <p>The trigger is published outside any transaction (see {@code onOrderSettled}), so without an
 * executor the {@code @TransactionalEventListener(fallbackExecution = true)} runs <em>inline on the
 * signal / fan-out thread</em> and walks every account with REST calls (~N² round-trips per signal).
 * Running it on this dedicated pool takes those exchange round-trips off the signal path.
 *
 * <p>Rejection policy is discard (like {@link ThreadPoolExecutor.DiscardPolicy}, plus a counter):
 * a post-order reconcile is a best-effort accelerator, not the source of truth — the 5s periodic
 * sweep is the backstop and {@code reconcileSymbol} is idempotent — so when the pool is saturated
 * it is correct to drop the extra trigger rather than block the signal thread (CallerRunsPolicy
 * would reintroduce the inline execution this pool exists to avoid) or throw back into
 * {@code publishEvent}. Drops are counted ({@code reconciliation_postorder_discarded_total}) so
 * pool saturation is visible instead of silent.
 */
@Configuration
@EnableAsync
public class AsyncReconciliationConfig {

    public static final String EXECUTOR = "reconciliationExecutor";

    @Bean(EXECUTOR)
    Executor reconciliationExecutor(MeterRegistry meterRegistry) {
        Counter discarded = meterRegistry.counter("reconciliation_postorder_discarded_total");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("recon-postorder-");
        executor.setRejectedExecutionHandler((r, pool) -> discarded.increment());
        executor.initialize();
        return executor;
    }
}
