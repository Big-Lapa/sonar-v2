package com.sonar.trading.exchange.model;

/**
 * Risk intent of an order, used by the Execution Gateway (T16) to apply the
 * close &gt; open priority (ADR-0018): when the venue is degraded, risk-reducing
 * orders must still get through while new entries are throttled / blocked.
 *
 * <ul>
 *   <li>{@link #ENTRY} — opens or increases exposure (OPEN, FLIP-open leg). Gated by the
 *       circuit breaker and fail-fast on the rate limiter.</li>
 *   <li>{@link #CLOSE_REDUCE} — closes or reduces exposure (CLOSE, REDUCE, cancels, force-close).
 *       Bypasses the circuit-breaker gate and waits on the rate limiter so it is not dropped.</li>
 * </ul>
 */
public enum OrderIntent {
    ENTRY,
    CLOSE_REDUCE
}
