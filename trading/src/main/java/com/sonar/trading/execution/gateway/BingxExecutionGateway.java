package com.sonar.trading.execution.gateway;

import com.sonar.trading.exchange.bingx.BingxFuturesClient;
import com.sonar.trading.exchange.bingx.BingxPlaceOrderRequest;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.health.SystemHealthService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;

/**
 * BingX binding of the exchange-agnostic {@link ResilientExecutionGateway} (T16): adds the
 * venue-typed {@code placeOrder} and BingX's one-shot {@code closeAllPositions}. All resilience
 * behaviour (circuit gate on entries, close&gt;open priority, retry, rate limiting, health
 * mirroring, metrics) lives in the base class.
 */
public class BingxExecutionGateway extends ResilientExecutionGateway implements ExecutionGateway {

    private final BingxFuturesClient client;

    public BingxExecutionGateway(BingxFuturesClient client,
                                 Retry retry,
                                 RateLimiter rateLimiter,
                                 CircuitBreaker circuitBreaker,
                                 SystemHealthService healthService,
                                 MeterRegistry meterRegistry,
                                 long closeWaitMs,
                                 String accountId) {
        super("BINGX", client, retry, rateLimiter, circuitBreaker,
                healthService, meterRegistry, closeWaitMs, accountId);
        this.client = client;
    }

    /** Venue-typed — not on {@link ExecutionGateway}, see its class doc. */
    public Map<String, Object> placeOrder(BingxPlaceOrderRequest request, OrderIntent intent) {
        return invoke(intent, "place", true, () -> client.placeOrder(request));
    }

    @Override
    public Map<String, Object> closeAllPositions(String symbol) {
        return invoke(OrderIntent.CLOSE_REDUCE, "close", true, () -> client.closeAllPositions(symbol));
    }
}
