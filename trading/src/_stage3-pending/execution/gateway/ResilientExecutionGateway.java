package com.sonar.trading.execution.gateway;

import com.sonar.trading.exchange.dto.ExchangeError;
import com.sonar.trading.exchange.exception.ExchangeException;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.exchange.spi.ExchangeRestClient;
import com.sonar.trading.health.SystemHealthService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Exchange-agnostic synchronous Resilience4j caller (T16, unified in the multi-account epic —
 * previously duplicated per venue). Subclasses bind one venue: they pass the exchange label and
 * the client's shared read/cancel slice here, and add the venue-typed operations (placeOrder,
 * closeAllPositions) as thin {@link #invoke} wrappers.
 *
 * <p>Per call:
 * <ol>
 *   <li><b>Circuit breaker</b> (entries only) — if OPEN, the entry is rejected immediately
 *       ({@code CIRCUIT_OPEN}). CLOSE/REDUCE bypass the gate (priority), so the breaker only ever
 *       sees entry traffic and recovers by probing entries in HALF_OPEN.</li>
 *   <li><b>Rate limiter</b> (token bucket) — entries fail fast ({@code RATE_LIMITED}) when the
 *       bucket is empty; CLOSE/REDUCE wait up to {@code closeWaitMs} and then proceed anyway,
 *       because failing to reduce risk is worse than risking a 429.</li>
 *   <li><b>Retry</b> — transient faults are retried with the same clientOrderId. For a non-idempotent
 *       ENTRY place only connect-phase {@code IO_ERROR} / pre-creation {@code 429} are retried; an
 *       ambiguous 5xx/{@code *_HTTP_ERROR} (the order may already exist) becomes {@code IO_ERROR_AMBIGUOUS}
 *       for PendingSubmitRecovery/reconciliation. CLOSE/REDUCE retry all transients (reduceOnly is safe).</li>
 * </ol>
 *
 * <p>The breaker state is mirrored into {@link SystemHealthService} under this instance's
 * accountId so the risk layer's health gate blocks new entries while the breaker is open (closes
 * stay exempt there too). Metrics carry {@code exchange} + {@code op} tags.
 */
public abstract class ResilientExecutionGateway implements ExchangeReadClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientExecutionGateway.class);

    public static final String ERR_CIRCUIT_OPEN = "CIRCUIT_OPEN";
    public static final String ERR_RATE_LIMITED = "RATE_LIMITED";
    private static final String METRIC_LATENCY = "order_submit_latency_ms";
    private static final String METRIC_REJECTED = "order_rejected_total";

    private final String exchange;
    private final ExchangeRestClient restClient;
    private final Retry retry;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;
    private final SystemHealthService healthService;
    private final MeterRegistry meterRegistry;
    private final long closeWaitMs;
    private final String accountId;

    protected ResilientExecutionGateway(String exchange,
                                        ExchangeRestClient restClient,
                                        Retry retry,
                                        RateLimiter rateLimiter,
                                        CircuitBreaker circuitBreaker,
                                        SystemHealthService healthService,
                                        MeterRegistry meterRegistry,
                                        long closeWaitMs,
                                        String accountId) {
        this.exchange = exchange;
        this.restClient = restClient;
        this.retry = retry;
        this.rateLimiter = rateLimiter;
        this.circuitBreaker = circuitBreaker;
        this.healthService = healthService;
        this.meterRegistry = meterRegistry;
        this.closeWaitMs = closeWaitMs;
        this.accountId = accountId;
        // Keep health in sync with automatic OPEN→HALF_OPEN→CLOSED transitions, not just call sites.
        circuitBreaker.getEventPublisher().onStateTransition(e -> mirrorCircuitStateToHealth());
        mirrorCircuitStateToHealth();
    }

    // ---- Shared call paths (identical signatures on every venue) ----

    @Override
    public Map<String, Object> getOrder(String symbol, String orderId, String clientOrderId) {
        // Status read: no circuit gate, no submit metric — but still retried and rate-limited.
        return invoke(OrderIntent.CLOSE_REDUCE, "get", false,
                () -> restClient.getOrder(symbol, orderId, clientOrderId));
    }

    @Override
    public Map<String, Object> getPositions(String symbol) {
        return invoke(OrderIntent.CLOSE_REDUCE, "positions", false,
                () -> restClient.getPositions(symbol));
    }

    @Override
    public Map<String, Object> getBalance() {
        return invoke(OrderIntent.CLOSE_REDUCE, "balance", false,
                restClient::getBalance);
    }

    public Map<String, Object> cancelOrder(String symbol, String orderId, String clientOrderId) {
        return invoke(OrderIntent.CLOSE_REDUCE, "cancel", true,
                () -> restClient.cancelOrder(symbol, orderId, clientOrderId));
    }

    // ---- Resilience machinery ----

    protected final Map<String, Object> invoke(OrderIntent intent, String op, boolean submitMetric,
                                               Supplier<Map<String, Object>> call) {
        boolean entry = intent == OrderIntent.ENTRY;

        // 1. Circuit breaker gate — entries only; closes/reduces have priority and bypass it.
        if (entry && !circuitBreaker.tryAcquirePermission()) {
            reject("circuit_open");
            mirrorCircuitStateToHealth();
            throw new ExchangeException(new ExchangeError(ERR_CIRCUIT_OPEN,
                    "execution circuit OPEN — new entries blocked (close/reduce still allowed)"));
        }

        // 2. Rate limiter.
        if (!acquireRatePermit(entry, op)) {
            if (entry) {
                circuitBreaker.releasePermission(); // give back the permit we just took
            }
            reject("rate_limited");
            throw new ExchangeException(new ExchangeError(ERR_RATE_LIMITED,
                    "execution rate limit exceeded for " + op));
        }

        // 3. Retry around the actual REST call; record outcome to the breaker (entries) + metrics.
        //    ENTRY places are non-idempotent: an ambiguous outcome (5xx / *_HTTP_ERROR — the order
        //    may already exist) must not be blindly retried, so it is remapped to IO_ERROR_AMBIGUOUS
        //    and left to PendingSubmitRecovery / reconciliation. CLOSE/REDUCE stay fully retryable
        //    (reduceOnly cannot flip through zero, so ensuring the risk-reducing action lands wins).
        Supplier<Map<String, Object>> guarded = entry ? guardEntryAmbiguousRetry(call) : call;
        long start = System.nanoTime();
        try {
            Map<String, Object> result = retry.executeSupplier(guarded::get);
            long durNanos = System.nanoTime() - start;
            if (entry) {
                circuitBreaker.onSuccess(durNanos, TimeUnit.NANOSECONDS);
            }
            recordLatency(submitMetric, op, durNanos);
            return result;
        } catch (RuntimeException ex) {
            long durNanos = System.nanoTime() - start;
            if (entry) {
                circuitBreaker.onError(durNanos, TimeUnit.NANOSECONDS, ex);
            }
            recordLatency(submitMetric, op, durNanos);
            throw ex;
        } finally {
            mirrorCircuitStateToHealth();
        }
    }

    /**
     * Wraps an ENTRY call so a transient-but-ambiguous failure is not retried. If the outcome is
     * transient yet not {@link TransientErrorClassifier#isRetryableForEntry entry-safe} (i.e. the
     * exchange may already have created the order), it is rethrown as {@code IO_ERROR_AMBIGUOUS} —
     * the retry predicate does not retry that code, and PendingSubmitRecovery / reconciliation
     * resolve it by querying the actual order state (same contract as a read-timeout on a place).
     * Entry-safe transients ({@code IO_ERROR}, {@code 429}) pass through unchanged and still retry.
     */
    private static Supplier<Map<String, Object>> guardEntryAmbiguousRetry(Supplier<Map<String, Object>> call) {
        return () -> {
            try {
                return call.get();
            } catch (ExchangeException e) {
                if (TransientErrorClassifier.isTransient(e) && !TransientErrorClassifier.isRetryableForEntry(e)) {
                    String origCode = e.getError() != null ? e.getError().code() : null;
                    String origMsg = e.getError() != null ? e.getError().message() : e.getMessage();
                    throw new ExchangeException(new ExchangeError("IO_ERROR_AMBIGUOUS",
                            "ambiguous entry outcome (was " + origCode + "): " + origMsg), e);
                }
                throw e;
            }
        };
    }

    /**
     * Entries fail fast (limiter configured with a zero timeout). CLOSE/REDUCE wait up to
     * {@code closeWaitMs} for a permit and then proceed anyway — priority over staying under the
     * limit. Returns {@code true} when the call should proceed.
     */
    private boolean acquireRatePermit(boolean entry, String op) {
        if (rateLimiter.acquirePermission()) {
            return true;
        }
        if (entry) {
            return false;
        }
        long deadline = System.nanoTime() + closeWaitMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (rateLimiter.acquirePermission()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("[GW-{}] rate limit exhausted — proceeding with risk-reducing {} without a permit (priority)",
                exchange, op);
        return true;
    }

    private void recordLatency(boolean submitMetric, String op, long durNanos) {
        if (submitMetric && meterRegistry != null) {
            meterRegistry.timer(METRIC_LATENCY, "op", op, "exchange", exchange)
                    .record(durNanos, TimeUnit.NANOSECONDS);
        }
    }

    private void reject(String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter(METRIC_REJECTED, "reason", reason, "exchange", exchange).increment();
        }
    }

    private void mirrorCircuitStateToHealth() {
        CircuitBreaker.State state = circuitBreaker.getState();
        boolean open = state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;
        healthService.setExecutionCircuitOpen(accountId, open);
    }
}
