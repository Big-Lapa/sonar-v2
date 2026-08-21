package com.sonar.trading.execution.gateway;

import com.sonar.trading.exchange.bingx.BingxFuturesClient;
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
 * Wires the Execution Gateway (T16) and its Resilience4j instances. Active only when
 * {@code trading.exchange=LIVE}; the default PAPER mode never creates these beans, so Phase 1
 * is untouched.
 */
@Configuration
@EnableConfigurationProperties(ExecutionGatewayProperties.class)
@ConditionalOnProperty(name = "trading.exchange", havingValue = "LIVE")
public class ExecutionGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(ExecutionGatewayConfig.class);

    @Bean
    public Retry executionRetry(ExecutionGatewayProperties props) {
        var r = props.getRetry();
        RetryConfig cfg = RetryConfig.custom()
                .maxAttempts(r.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        Duration.ofMillis(r.getWaitMs()), r.getBackoffMultiplier()))
                .retryOnException(TransientErrorClassifier::isTransient)
                .failAfterMaxAttempts(true)
                .build();
        return Retry.of("bingx-execution", cfg);
    }

    @Bean
    public RateLimiter executionRateLimiter(ExecutionGatewayProperties props) {
        var rl = props.getRateLimiter();
        // Zero timeout → acquirePermission() is non-blocking; CLOSE/REDUCE waiting is handled in
        // the gateway so entries can fail fast while closes are given a grace budget.
        RateLimiterConfig cfg = RateLimiterConfig.custom()
                .limitForPeriod(rl.getLimitForPeriod())
                .limitRefreshPeriod(Duration.ofMillis(rl.getRefreshPeriodMs()))
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiter.of("bingx-execution", cfg);
    }

    @Bean
    public CircuitBreaker executionCircuitBreaker(ExecutionGatewayProperties props) {
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
        return CircuitBreaker.of("bingx-execution", cfg);
    }

    @Bean
    public ExecutionGateway executionGateway(BingxFuturesClient client,
                                             Retry executionRetry,
                                             RateLimiter executionRateLimiter,
                                             CircuitBreaker executionCircuitBreaker,
                                             SystemHealthService healthService,
                                             MeterRegistry meterRegistry,
                                             ExecutionGatewayProperties props) {
        log.info("Execution Gateway active (LIVE): retry={}x rateLimit={}/{}ms breaker={}%@{}",
                props.getRetry().getMaxAttempts(),
                props.getRateLimiter().getLimitForPeriod(),
                props.getRateLimiter().getRefreshPeriodMs(),
                props.getCircuitBreaker().getFailureRateThreshold(),
                props.getCircuitBreaker().getSlidingWindowSize());
        return new BingxExecutionGateway(client, executionRetry, executionRateLimiter,
                executionCircuitBreaker, healthService, meterRegistry,
                props.getRateLimiter().getCloseWaitMs(),
                com.sonar.trading.health.SystemHealthService.DEFAULT_ACCOUNT);
    }
}
