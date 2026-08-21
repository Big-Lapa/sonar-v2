package com.sonar.trading.execution.gateway;

import java.util.Map;

/**
 * Read-only slice of an exchange gateway (P4): the account-state queries reconciliation needs,
 * without the order-mutation surface. Both {@link BingxExecutionGateway} and
 * {@link BinanceExecutionGateway} implement it, so a per-account reconciler can share the same
 * resilient gateway instance the account's execution adapter uses (one rate-limiter/circuit per
 * account), while depending only on reads.
 *
 * <p>All methods return the raw exchange JSON map; parsing stays in the pure per-exchange parsers.
 */
public interface ExchangeReadClient {

    /** Query order status (read path — retried and rate-limited, but never circuit-gated). */
    Map<String, Object> getOrder(String symbol, String orderId, String clientOrderId);

    /** Current positions, optionally scoped to an exchange-form symbol (read path). */
    Map<String, Object> getPositions(String symbol);

    /** Account balance / equity (read path). Used by reconciliation + PnL (T17/FU-3). */
    Map<String, Object> getBalance();
}
