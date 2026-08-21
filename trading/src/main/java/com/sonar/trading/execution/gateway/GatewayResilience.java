package com.sonar.trading.execution.gateway;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.time.Duration;

/**
 * Builds Resilience4j instances from {@link ExecutionGatewayProperties}, shared by the singleton
 * LIVE gateway configs and the per-account {@code AccountAdapterFactory} (P1). Factored out so the
 * retry/rate-limiter/circuit-breaker tuning lives in exactly one place; each caller passes a unique
 * {@code name} so per-account instances don't collide in the Resilience4j registry.
 */
public final class GatewayResilience {

    private GatewayResilience() {}

    public static Retry retry(ExecutionGatewayProperties props, String name) {
        var r = props.getRetry();
        RetryConfig cfg = RetryConfig.custom()
                .maxAttempts(r.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        Duration.ofMillis(r.getWaitMs()), r.getBackoffMultiplier()))
                .retryOnException(TransientErrorClassifier::isTransient)
                .failAfterMaxAttempts(true)
                .build();
        return Retry.of(name, cfg);
    }

    public static RateLimiter rateLimiter(ExecutionGatewayProperties props, String name) {
        var rl = props.getRateLimiter();
        RateLimiterConfig cfg = RateLimiterConfig.custom()
                .limitForPeriod(rl.getLimitForPeriod())
                .limitRefreshPeriod(Duration.ofMillis(rl.getRefreshPeriodMs()))
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiter.of(name, cfg);
    }

    public static CircuitBreaker circuitBreaker(ExecutionGatewayProperties props, String name) {
        var cb = props.getCircuitBreaker();
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(cb.getSlidingWindowSize())
                .minimumNumberOfCalls(cb.getMinimumNumberOfCalls())
                .failureRateThreshold(cb.getFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(cb.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(cb.getPermittedCallsInHalfOpen())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        return CircuitBreaker.of(name, cfg);
    }
}
