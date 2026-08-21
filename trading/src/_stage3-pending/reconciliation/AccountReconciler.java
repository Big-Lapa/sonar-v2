package com.sonar.trading.reconciliation;

import com.sonar.trading.domain.entity.OrderEntity;
import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangeOrderStatus;
import com.sonar.trading.reconciliation.model.ExchangePosition;

import java.util.List;
import java.util.Optional;

/**
 * Per-account view of one exchange for reconciliation (P4). Encapsulates everything
 * exchange-specific — the read gateway, response parsing, symbol conversion and the equity
 * asset — so {@link ReconciliationService} can loop accounts without knowing venues.
 *
 * <p>One instance per LIVE (BINGX/BINANCE) account, registered in {@link AccountReconcilers} by
 * the adapter factory. PAPER accounts have no reconciler — there is no exchange to reconcile.
 */
public interface AccountReconciler {

    /** The account this reconciler reads (the account dimension of positions/PnL). */
    String accountId();

    /** Venue label ({@code BINGX} / {@code BINANCE}) used on emitted events and logs. */
    String exchange();

    /**
     * Exchange positions, parsed into core-symbol models. {@code coreSymbolOrNull} scopes the
     * query to one symbol; {@code null} fetches all. Throws on a failed read — the caller treats
     * that as "exchange unreachable" (safe default: block new entries).
     */
    List<ExchangePosition> positions(String coreSymbolOrNull);

    /** Balance of the account's equity asset; empty when the venue returned no usable row. Throws on a failed read. */
    Optional<ExchangeBalance> balance();

    /**
     * Exchange-reported status of one locally-active order, or {@code null} when the read failed
     * (logged inside; the order is skipped this cycle and retried the next).
     */
    ExchangeOrderStatus orderStatus(OrderEntity order);
}
