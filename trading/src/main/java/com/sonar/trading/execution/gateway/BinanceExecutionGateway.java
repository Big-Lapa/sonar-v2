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
 * Binance USDT-M binding of the exchange-agnostic {@link ResilientExecutionGateway} (mirrors
 * {@link BybitExecutionGateway} / {@link BingxExecutionGateway}): adds the venue-typed
 * {@code placeOrder}, {@code setLeverage} and {@code cancelAllOpenOrders}. Binance has no one-shot
 * close-all endpoint — {@code BinanceExecutionAdapter} emulates it with reduce-only market orders
 * through {@code placeOrder}, same as BingX's synthesized path.
 *
 * <p>{@code setLeverage}/{@code cancelAllOpenOrders} run as CLOSE_REDUCE, not ENTRY: both are
 * housekeeping calls whose failure must not poison the entry circuit breaker (a bracket-limited
 * key or missing leverage permission would otherwise trip the breaker and block every entry on
 * the account, not just the leverage change). They still get retry + rate limiting, just not the
 * breaker's permission/onError bookkeeping.
 *
 * <p>Deliberately does NOT implement {@link ExecutionGateway}: that interface's
 * {@code closeAllPositions} has no Binance equivalent (no bulk endpoint) and every real caller
 * needs {@code BinanceExecutionAdapter}'s synthesized version (read positions, reduce-only close
 * each) anyway — a method here that could only ever throw would be a trap for a future caller
 * going through the interface type instead.
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

    /** Venue-typed — not on {@link ExecutionGateway}, see its class doc. */
    public Map<String, Object> placeOrder(BinancePlaceOrderRequest request, OrderIntent intent) {
        return invoke(intent, "place", true, () -> client.placeOrder(request));
    }

    public Map<String, Object> setLeverage(String symbol, int leverage) {
        return invoke(OrderIntent.CLOSE_REDUCE, "leverage", false, () -> client.setLeverage(symbol, leverage));
    }

    public Map<String, Object> cancelAllOpenOrders(String symbol) {
        return invoke(OrderIntent.CLOSE_REDUCE, "cancelAll", false, () -> client.cancelAllOpenOrders(symbol));
    }
}
