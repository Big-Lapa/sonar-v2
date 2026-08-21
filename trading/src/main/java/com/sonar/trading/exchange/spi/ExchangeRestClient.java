package com.sonar.trading.exchange.spi;

import java.util.Map;

/**
 * Exchange-agnostic slice of a signed REST client: the operations whose signatures are identical
 * across venues (cancel + the account reads). {@code BingxFuturesClient} and
 * {@code BinanceFuturesClient} extend this, which lets the resilient gateway base class implement
 * the shared call paths once; venue-specific operations (typed placeOrder, closeAllPositions,
 * price queries) stay on the concrete client interfaces.
 *
 * <p>All methods return the raw exchange JSON map — mapping/parsing stays in the per-exchange
 * mappers and parsers.
 */
public interface ExchangeRestClient {

    Map<String, Object> cancelOrder(String symbol, String orderId, String clientOrderId);

    Map<String, Object> getOrder(String symbol, String orderId, String clientOrderId);

    /** Current positions, optionally scoped to an exchange-form symbol. */
    Map<String, Object> getPositions(String symbol);

    /** Account balances / equity. */
    Map<String, Object> getBalance();
}
