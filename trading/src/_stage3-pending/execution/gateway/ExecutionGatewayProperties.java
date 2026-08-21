package com.sonar.trading.execution.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the Execution Gateway (T16, ADR-0007/0018). Bound from {@code trading.gateway.*}.
 *
 * <p>The gateway is synchronous (no async queue — deferred as a future enhancement) and only
 * active on the LIVE path. Defaults are conservative for the rare intraday-signal cadence on
 * BingX VST; tune per the venue's published rate limits.
 */
@ConfigurationProperties(prefix = "trading.gateway")
public class ExecutionGatewayProperties {

    private final Retry retry = new Retry();
    private final RateLimiter rateLimiter = new RateLimiter();
    private final CircuitBreaker circuitBreaker = new CircuitBreaker();

    public Retry getRetry() { return retry; }
    public RateLimiter getRateLimiter() { return rateLimiter; }
    public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }

    /** Idempotent retry on transient faults (network / timeout / 5xx / 429). Same clientOrderId → BingX dedups. */
    public static class Retry {
        private int maxAttempts = 3;
        private long waitMs = 200;
        private double backoffMultiplier = 2.0;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getWaitMs() { return waitMs; }
        public void setWaitMs(long waitMs) { this.waitMs = waitMs; }
        public double getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
    }

    /** Token bucket to stay under the venue rate limit (avoid 429). */
    public static class RateLimiter {
        /** Permits granted each refresh period. */
        private int limitForPeriod = 10;
        /** Refresh period for the bucket, in ms. */
        private long refreshPeriodMs = 1_000;
        /** Max wait a CLOSE/REDUCE order will block for a permit before proceeding anyway (priority). */
        private long closeWaitMs = 500;

        public int getLimitForPeriod() { return limitForPeriod; }
        public void setLimitForPeriod(int limitForPeriod) { this.limitForPeriod = limitForPeriod; }
        public long getRefreshPeriodMs() { return refreshPeriodMs; }
        public void setRefreshPeriodMs(long refreshPeriodMs) { this.refreshPeriodMs = refreshPeriodMs; }
        public long getCloseWaitMs() { return closeWaitMs; }
        public void setCloseWaitMs(long closeWaitMs) { this.closeWaitMs = closeWaitMs; }
    }

    /** Circuit breaker: too many failures → OPEN → new entries blocked (closes still pass). */
    public static class CircuitBreaker {
        /** Count-based sliding window size. */
        private int slidingWindowSize = 10;
        /** Minimum calls before the failure rate is evaluated. */
        private int minimumNumberOfCalls = 5;
        /** Failure-rate percentage that trips the breaker. */
        private float failureRateThreshold = 50f;
        /** How long the breaker stays OPEN before probing (half-open), in ms. */
        private long waitDurationInOpenStateMs = 30_000;
        /** Number of probe (entry) calls allowed in HALF_OPEN. */
        private int permittedCallsInHalfOpen = 2;

        public int getSlidingWindowSize() { return slidingWindowSize; }
        public void setSlidingWindowSize(int slidingWindowSize) { this.slidingWindowSize = slidingWindowSize; }
        public int getMinimumNumberOfCalls() { return minimumNumberOfCalls; }
        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) { this.minimumNumberOfCalls = minimumNumberOfCalls; }
        public float getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(float failureRateThreshold) { this.failureRateThreshold = failureRateThreshold; }
        public long getWaitDurationInOpenStateMs() { return waitDurationInOpenStateMs; }
        public void setWaitDurationInOpenStateMs(long waitDurationInOpenStateMs) { this.waitDurationInOpenStateMs = waitDurationInOpenStateMs; }
        public int getPermittedCallsInHalfOpen() { return permittedCallsInHalfOpen; }
        public void setPermittedCallsInHalfOpen(int permittedCallsInHalfOpen) { this.permittedCallsInHalfOpen = permittedCallsInHalfOpen; }
    }
}
