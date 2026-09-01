package com.sonar.trading.execution.gateway;

import com.sonar.trading.exchange.bingx.BingxFuturesClient;
import com.sonar.trading.exchange.bybit.BybitFuturesClient;
import com.sonar.trading.health.SystemHealthService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires the Execution Gateway (T16) and its Resilience4j instances, one full stack per venue
 * (full multi-venue routing, not a switch, per the grilled Bybit-integration plan).
 * Active only when {@code trading.exchange=LIVE}; the default PAPER mode never creates these
 * beans, so Phase 1 is untouched. Each venue gets its own named Resilience4j registries (so a
 * BingX outage never trips Bybit's breaker or vice versa) and its own health accountId.
 */
@Configuration
@EnableConfigurationProperties(ExecutionGatewayProperties.class)
@ConditionalOnProperty(name = "trading.exchange", havingValue = "LIVE")
public class ExecutionGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(ExecutionGatewayConfig.class);

    // ---- BingX stack ----

    @Bean
    public Retry bingxExecutionRetry(ExecutionGatewayProperties props) {
        return buildRetry("bingx-execution", props);
    }

    @Bean
    public RateLimiter bingxExecutionRateLimiter(ExecutionGatewayProperties props) {
        return buildRateLimiter("bingx-execution", props);
    }

    @Bean
    public CircuitBreaker bingxExecutionCircuitBreaker(ExecutionGatewayProperties props) {
        return buildCircuitBreaker("bingx-execution", props);
    }

    @Bean
    public BingxExecutionGateway bingxExecutionGateway(BingxFuturesClient client,
                                                        Retry bingxExecutionRetry,
                                                        RateLimiter bingxExecutionRateLimiter,
                                                        CircuitBreaker bingxExecutionCircuitBreaker,
                                                        SystemHealthService healthService,
                                                        MeterRegistry meterRegistry,
                                                        ExecutionGatewayProperties props) {
        log.info("Execution Gateway active (LIVE, BINGX): retry={}x rateLimit={}/{}ms breaker={}%@{}",
                props.getRetry().getMaxAttempts(),
                props.getRateLimiter().getLimitForPeriod(),
                props.getRateLimiter().getRefreshPeriodMs(),
                props.getCircuitBreaker().getFailureRateThreshold(),
                props.getCircuitBreaker().getSlidingWindowSize());
        return new BingxExecutionGateway(client, bingxExecutionRetry, bingxExecutionRateLimiter,
                bingxExecutionCircuitBreaker, healthService, meterRegistry,
                props.getRateLimiter().getCloseWaitMs(), SystemHealthService.DEFAULT_ACCOUNT);
    }

    // ---- Bybit stack ----

    @Bean
    public Retry bybitExecutionRetry(ExecutionGatewayProperties props) {
        return buildRetry("bybit-execution", props);
    }

    @Bean
    public RateLimiter bybitExecutionRateLimiter(ExecutionGatewayProperties props) {
        return buildRateLimiter("bybit-execution", props);
    }

    @Bean
    public CircuitBreaker bybitExecutionCircuitBreaker(ExecutionGatewayProperties props) {
        return buildCircuitBreaker("bybit-execution", props);
    }

    @Bean
    public BybitExecutionGateway bybitExecutionGateway(BybitFuturesClient client,
                                                        Retry bybitExecutionRetry,
                                                        RateLimiter bybitExecutionRateLimiter,
                                                        CircuitBreaker bybitExecutionCircuitBreaker,
                                                        SystemHealthService healthService,
                                                        MeterRegistry meterRegistry,
                                                        ExecutionGatewayProperties props) {
        log.info("Execution Gateway active (LIVE, BYBIT): retry={}x rateLimit={}/{}ms breaker={}%@{}",
                props.getRetry().getMaxAttempts(),
                props.getRateLimiter().getLimitForPeriod(),
                props.getRateLimiter().getRefreshPeriodMs(),
                props.getCircuitBreaker().getFailureRateThreshold(),
                props.getCircuitBreaker().getSlidingWindowSize());
        return new BybitExecutionGateway(client, bybitExecutionRetry, bybitExecutionRateLimiter,
                bybitExecutionCircuitBreaker, healthService, meterRegistry,
                props.getRateLimiter().getCloseWaitMs(), "BYBIT");
    }

    // ---- Shared builders (same tuning knobs for every venue for now — trading.gateway.*) ----

    private static Retry buildRetry(String name, ExecutionGatewayProperties props) {
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

    private static RateLimiter buildRateLimiter(String name, ExecutionGatewayProperties props) {
        var rl = props.getRateLimiter();
        // Zero timeout → acquirePermission() is non-blocking; CLOSE/REDUCE waiting is handled in
        // the gateway so entries can fail fast while closes are given a grace budget.
        RateLimiterConfig cfg = RateLimiterConfig.custom()
                .limitForPeriod(rl.getLimitForPeriod())
                .limitRefreshPeriod(Duration.ofMillis(rl.getRefreshPeriodMs()))
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiter.of(name, cfg);
    }

    private static CircuitBreaker buildCircuitBreaker(String name, ExecutionGatewayProperties props) {
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
