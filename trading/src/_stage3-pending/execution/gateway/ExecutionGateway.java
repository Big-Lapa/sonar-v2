package com.sonar.trading.execution.gateway;

import com.sonar.trading.exchange.bingx.BingxPlaceOrderRequest;
import com.sonar.trading.exchange.model.OrderIntent;

import java.util.Map;

/**
 * Resilience layer (T16) between {@code LiveExecutionAdapter} and the BingX REST client.
 * Adds idempotent retries, rate limiting and a circuit breaker with close&gt;open priority
 * (ADR-0007/0018). Synchronous by design — an async submission queue is a deferred enhancement.
 *
 * <p>Only wired on the LIVE path; PAPER never sees it. Methods return the raw BingX JSON map,
 * exactly like {@code BingxFuturesClient}, so the adapter's mapping is unchanged.
 */
public interface ExecutionGateway extends ExchangeReadClient {

    /** Submit an order. {@code intent} drives the circuit-breaker gate and rate-limiter behaviour. */
    Map<String, Object> placeOrder(BingxPlaceOrderRequest request, OrderIntent intent);

    /** Cancel an order (risk-reducing → treated as CLOSE/REDUCE priority). */
    Map<String, Object> cancelOrder(String symbol, String orderId, String clientOrderId);

    /** Force-close positions (risk-reducing → CLOSE/REDUCE priority). */
    Map<String, Object> closeAllPositions(String symbol);
}
