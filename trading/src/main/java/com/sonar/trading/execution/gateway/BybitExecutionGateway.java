package com.sonar.trading.execution.gateway;

import com.sonar.trading.exchange.bybit.BybitFuturesClient;
import com.sonar.trading.exchange.bybit.BybitPlaceOrderRequest;
import com.sonar.trading.exchange.bybit.BybitTriggerBy;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.health.SystemHealthService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;

/**
 * Bybit binding of the exchange-agnostic {@link ResilientExecutionGateway} (mirrors
 * {@link BingxExecutionGateway}): adds the venue-typed {@code placeOrder} and Bybit's synthesized
 * {@code closeAllPositions} (no bulk endpoint on V5 — see {@code BybitFuturesHttpService}). All
 * resilience behaviour (circuit gate on entries, close&gt;open priority, retry, rate limiting,
 * health mirroring, metrics) lives in the base class.
 */
public class BybitExecutionGateway extends ResilientExecutionGateway implements ExecutionGateway {

    private final BybitFuturesClient client;

    public BybitExecutionGateway(BybitFuturesClient client,
                                 Retry retry,
                                 RateLimiter rateLimiter,
                                 CircuitBreaker circuitBreaker,
                                 SystemHealthService healthService,
                                 MeterRegistry meterRegistry,
                                 long closeWaitMs,
                                 String accountId) {
        super("BYBIT", client, retry, rateLimiter, circuitBreaker,
                healthService, meterRegistry, closeWaitMs, accountId);
        this.client = client;
    }

    /** Venue-typed — not on {@link ExecutionGateway}, see its class doc. */
    public Map<String, Object> placeOrder(BybitPlaceOrderRequest request, OrderIntent intent) {
        return invoke(intent, "place", true, () -> client.placeOrder(request));
    }

    /**
     * Position-level TP/SL. Venue-typed, so it lives here rather than on {@link ExecutionGateway}.
     *
     * <p>Runs as CLOSE_REDUCE (risk-reducing → close&gt;open priority, never circuit-gated shut
     * while entries are). Unlike {@code placeOrder}, this one is genuinely safe to retry: it sets
     * an absolute price on the position rather than creating an order, so a retried call after an
     * ambiguous timeout converges on the same state instead of stacking a second stop.
     */
    public Map<String, Object> setTradingStop(String symbol, String stopLoss, String takeProfit,
                                              BybitTriggerBy triggerBy) {
        return invoke(OrderIntent.CLOSE_REDUCE, "close", true,
                () -> client.setTradingStop(symbol, stopLoss, takeProfit, triggerBy));
    }

    @Override
    public Map<String, Object> closeAllPositions(String symbol) {
        return invoke(OrderIntent.CLOSE_REDUCE, "close", true, () -> client.closeAllPositions(symbol));
    }

    /** Venue-typed — not on {@link ExecutionGateway}. Runs as ENTRY: it's part of opening a position. */
    public Map<String, Object> setLeverage(String symbol, int leverage) {
        return invoke(OrderIntent.ENTRY, "leverage", false, () -> client.setLeverage(symbol, leverage));
    }
}
