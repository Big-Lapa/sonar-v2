package com.sonar.trading.execution.gateway;

import java.util.Map;

/**
 * Resilience layer (T16) between a venue's execution adapter and its REST client. Adds
 * idempotent retries, rate limiting and a circuit breaker with close&gt;open priority
 * (ADR-0007/0018). Synchronous by design — an async submission queue is a deferred enhancement.
 *
 * <p>Only wired on the LIVE path; PAPER never sees it. Methods return the raw venue JSON map,
 * exactly like the venue's REST client, so the adapter's mapping is unchanged.
 *
 * <p>{@code placeOrder} is intentionally NOT here: its request type is venue-typed
 * ({@code BingxPlaceOrderRequest}, {@code BybitPlaceOrderRequest}, ...), so it lives as a typed
 * method on each concrete gateway ({@link BingxExecutionGateway}, {@link BybitExecutionGateway}).
 * This interface only carries the operations whose signature is identical across venues.
 */
public interface ExecutionGateway extends ExchangeReadClient {

    /** Cancel an order (risk-reducing → treated as CLOSE/REDUCE priority). */
    Map<String, Object> cancelOrder(String symbol, String orderId, String clientOrderId);

    /** Force-close positions (risk-reducing → CLOSE/REDUCE priority). */
    Map<String, Object> closeAllPositions(String symbol);
}
