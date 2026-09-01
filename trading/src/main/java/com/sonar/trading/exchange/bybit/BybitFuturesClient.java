package com.sonar.trading.exchange.bybit;

import com.sonar.trading.exchange.spi.ExchangeRestClient;

import java.util.Map;

/**
 * Bybit V5 USDT-perpetual ({@code category=linear}) signed client surface used by execution /
 * reconciliation. Extracted as an interface so collaborators can be mocked in tests, mirroring
 * {@link com.sonar.trading.exchange.bingx.BingxFuturesClient}.
 * Implemented by {@link com.sonar.trading.exchange.bybit.http.BybitFuturesHttpService}.
 * The exchange-agnostic operations (cancel + account reads) come from {@link ExchangeRestClient}.
 */
public interface BybitFuturesClient extends ExchangeRestClient {

    Map<String, Object> placeOrder(BybitPlaceOrderRequest request);

    /**
     * Bybit V5 has no bulk close-all endpoint (unlike BingX) — this is synthesized: read open
     * positions, place a reduce-only MARKET close per non-flat one, then re-read positions and
     * surface any that are still non-flat as a failure (grilled Q3: verify-after-close, never
     * silently trust the close requests landed).
     */
    Map<String, Object> closeAllPositions(String symbol);

    /**
     * Set (or clear) the POSITION-level stop-loss / take-profit — {@code /v5/position/trading-stop},
     * {@code tpslMode=Full}. This is the same slot TP/SL attached to an entry order land in, so
     * setting it REPLACES whatever protection the position already carries rather than adding a
     * second, competing stop the way a standalone conditional order would.
     *
     * <p>Each price is tri-state: {@code null} leaves that side untouched, {@code "0"} clears it,
     * anything else sets it. Idempotent — re-sending the same absolute price is a no-op on the
     * exchange, which is what makes this call safe for the gateway to retry.
     */
    Map<String, Object> setTradingStop(String symbol, String stopLoss, String takeProfit, BybitTriggerBy triggerBy);
    /** All open orders for a symbol, including untriggered conditional TP/SL orders. */
    Map<String, Object> getOpenOrders(String symbol);

    /** Latest ticker for a symbol (last/mark price). */
    Map<String, Object> getPrice(String symbol);

    /**
     * Realized PnL + funding/fees ledger, approximating BingX's single {@code getIncome} contract.
     * Internally merges two real Bybit endpoints — {@code /v5/position/closed-pnl} (realized PnL)
     * and {@code /v5/account/transaction-log} (funding/fees) — grilled Q4: accepted as a documented
     * parity gap, not a strict line-by-line match of BingX's ledger.
     */
    Map<String, Object> getIncome(String incomeType, Long startTime, Long endTime, Integer limit);
}
