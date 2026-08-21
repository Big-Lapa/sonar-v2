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
     * Плечо символа перед ENTRY (per-strategy параметр); идемпотентно на том же значении.
     * Идёт CLOSE-путём, как и {@link #cancelAllOpenOrders}: служебный вызов через invoke(ENTRY,...)
     * кормил ошибками ENTRY-брейкер — систематический отказ /fapi/v1/leverage (бракет ниже 20x,
     * права ключа) открывал circuit и глушил ВСЕ входы аккаунта, включая чужие стратегии
     * (ревью #88). CLOSE_REDUCE не участвует в circuitBreaker.onError и не берёт его permission,
     * но сохраняет rate-limit и retry — голый вызов мимо всего контура терял и их. Отказ
     * обрабатывает адаптер (warn + вход на текущем плече + TTL-кэш отказа).
     */
    public Map<String, Object> setLeverage(String symbol, int leverage) {
        return invoke(OrderIntent.CLOSE_REDUCE, "leverage", false, () -> client.setLeverage(symbol, leverage));
    }

    /** Снять ВСЕ открытые ордера символа (условные TP/SL ноги). Идёт CLOSE-путём: уборка
     *  снижает риск и обязана проходить под деградацией, permission брейкера не берётся. */
    public Map<String, Object> cancelAllOpenOrders(String symbol) {
        return invoke(OrderIntent.CLOSE_REDUCE, "cancelAll", false, () -> client.cancelAllOpenOrders(symbol));
    }
}
