package com.sonar.trading.execution.gateway;

import com.sonar.trading.exchange.binance.BinanceFuturesClient;
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
 * Wires the Binance {@link BinanceExecutionGateway} and its Resilience4j instances. Active only when
 * {@code trading.exchange=BINANCE}. Shares tuning ({@link ExecutionGatewayProperties},
 * {@code trading.gateway.*}) with the BingX gateway — the two paths are mutually exclusive by mode,
 * so the Resilience4j instances never coexist.
 */
@Configuration
@EnableConfigurationProperties(ExecutionGatewayProperties.class)
@ConditionalOnProperty(name = "trading.exchange", havingValue = "BINANCE")
public class BinanceExecutionGatewayConfig {

    private static final Logger log = LoggerFactory.getLogger(BinanceExecutionGatewayConfig.class);

    @Bean
    public Retry binanceExecutionRetry(ExecutionGatewayProperties props) {
        var r = props.getRetry();
        RetryConfig cfg = RetryConfig.custom()
                .maxAttempts(r.getMaxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        Duration.ofMillis(r.getWaitMs()), r.getBackoffMultiplier()))
                .retryOnException(TransientErrorClassifier::isTransient)
                .failAfterMaxAttempts(true)
                .build();
        return Retry.of("binance-execution", cfg);
    }

    @Bean
    public RateLimiter binanceExecutionRateLimiter(ExecutionGatewayProperties props) {
        var rl = props.getRateLimiter();
        RateLimiterConfig cfg = RateLimiterConfig.custom()
                .limitForPeriod(rl.getLimitForPeriod())
                .limitRefreshPeriod(Duration.ofMillis(rl.getRefreshPeriodMs()))
                .timeoutDuration(Duration.ZERO)
                .build();
        return RateLimiter.of("binance-execution", cfg);
    }

    @Bean
    public CircuitBreaker binanceExecutionCircuitBreaker(ExecutionGatewayProperties props) {
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
        return CircuitBreaker.of("binance-execution", cfg);
    }

    @Bean
    public BinanceExecutionGateway binanceExecutionGateway(BinanceFuturesClient client,
                                                           Retry binanceExecutionRetry,
                                                           RateLimiter binanceExecutionRateLimiter,
                                                           CircuitBreaker binanceExecutionCircuitBreaker,
                                                           SystemHealthService healthService,
                                                           MeterRegistry meterRegistry,
                                                           ExecutionGatewayProperties props) {
        log.info("Binance Execution Gateway active (BINANCE): retry={}x rateLimit={}/{}ms breaker={}%@{}",
                props.getRetry().getMaxAttempts(),
                props.getRateLimiter().getLimitForPeriod(),
                props.getRateLimiter().getRefreshPeriodMs(),
                props.getCircuitBreaker().getFailureRateThreshold(),
                props.getCircuitBreaker().getSlidingWindowSize());
        return new BinanceExecutionGateway(client, binanceExecutionRetry, binanceExecutionRateLimiter,
                binanceExecutionCircuitBreaker, healthService, meterRegistry,
                props.getRateLimiter().getCloseWaitMs(),
                com.sonar.trading.health.SystemHealthService.DEFAULT_ACCOUNT);
    }
}
