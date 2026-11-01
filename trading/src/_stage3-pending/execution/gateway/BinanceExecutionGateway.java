package com.sonar.trading.execution.gateway;

import com.sonar.trading.exchange.binance.BinanceFuturesClient;
import com.sonar.trading.exchange.binance.BinancePlaceOrderRequest;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.health.SystemHealthService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Map;

/**
 * Binance USDT-M binding of the exchange-agnostic {@link ResilientExecutionGateway}: adds only the
 * venue-typed {@code placeOrder}. Binance has no one-shot close-all endpoint — the adapter
 * emulates it with reduce-only market orders through {@code placeOrder}.
 */
public class BinanceExecutionGateway extends ResilientExecutionGateway {

    private final BinanceFuturesClient client;

    public BinanceExecutionGateway(BinanceFuturesClient client,
                                   Retry retry,
                                   RateLimiter rateLimiter,
                                   CircuitBreaker circuitBreaker,
                                   SystemHealthService healthService,
                                   MeterRegistry meterRegistry,
                                   long closeWaitMs,
                                   String accountId) {
        super("BINANCE", client, retry, rateLimiter, circuitBreaker,
                healthService, meterRegistry, closeWaitMs, accountId);
        this.client = client;
    }

    public Map<String, Object> placeOrder(BinancePlaceOrderRequest request, OrderIntent intent) {
        return invoke(intent, "place", true, () -> client.placeOrder(request));
    }

    /**
     * Symbol leverage before ENTRY (per-strategy parameter); idempotent on the same value.
     * Goes via the CLOSE path, like {@link #cancelAllOpenOrders}: a service call via invoke(ENTRY,...)
     * fed errors to the ENTRY breaker — a systematic rejection of /fapi/v1/leverage (bracket below 20x,
     * key permissions) opened the circuit and silenced ALL entries of the account, including other strategies'
     * (review #88). CLOSE_REDUCE does not participate in circuitBreaker.onError and does not take its permission,
     * but keeps rate-limit and retry — a bare call bypassing the whole pipeline lost those too. The rejection
     * is handled by the adapter (warn + entry at the current leverage + TTL cache of the rejection).
     */
    public Map<String, Object> setLeverage(String symbol, int leverage) {
        return invoke(OrderIntent.CLOSE_REDUCE, "leverage", false, () -> client.setLeverage(symbol, leverage));
    }

    /** Cancel ALL open orders of a symbol (conditional TP/SL legs). Goes via the CLOSE path: cleanup
     *  reduces risk and must pass under degradation, the breaker's permission is not taken. */
    public Map<String, Object> cancelAllOpenOrders(String symbol) {
        return invoke(OrderIntent.CLOSE_REDUCE, "cancelAll", false, () -> client.cancelAllOpenOrders(symbol));
    }
}
