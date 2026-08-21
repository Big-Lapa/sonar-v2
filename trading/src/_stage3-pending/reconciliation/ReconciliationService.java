package com.sonar.trading.reconciliation;

import com.sonar.trading.domain.entity.OrderEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.repository.OrderRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.messaging.producer.TradingStateProducer;
import com.sonar.trading.pnl.DailyPnlTracker;
import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangeOrderStatus;
import com.sonar.trading.reconciliation.model.ExchangePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Reconciliation orchestrator (T17, per-account since P4): loops the registered
 * {@link AccountReconciler}s, pulls each account's exchange truth through its own resilient
 * gateway, and delegates the transactional DB reconciliation to {@link SymbolReconciler} keyed by
 * {@code (accountId, symbol)}. Also closes FU-3 per account — samples exchange equity for PnL and
 * emits {@code balance.updated}. With no reconcilers registered (PAPER-only) every cycle is a no-op.
 *
 * <p>The exchange is authoritative: local state is overwritten to match, never the reverse.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final AccountReconcilers reconcilers;
    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final SymbolReconciler symbolReconciler;
    private final DailyPnlTracker pnlTracker;
    private final TradingStateProducer stateProducer;
    private final com.sonar.trading.health.SystemHealthService healthService;

    public ReconciliationService(AccountReconcilers reconcilers,
                                 PositionRepository positionRepository,
                                 OrderRepository orderRepository,
                                 SymbolReconciler symbolReconciler,
                                 DailyPnlTracker pnlTracker,
                                 TradingStateProducer stateProducer,
                                 com.sonar.trading.health.SystemHealthService healthService) {
        this.reconcilers = reconcilers;
        this.positionRepository = positionRepository;
        this.orderRepository = orderRepository;
        this.symbolReconciler = symbolReconciler;
        this.pnlTracker = pnlTracker;
        this.stateProducer = stateProducer;
        this.healthService = healthService;
    }

    // ---- Full cycle (periodic + startup) ----

    /**
     * Reconcile every account against its exchange. Accounts are isolated: one account's failed
     * bulk read is logged and the remaining accounts still reconcile, but the failure is rethrown
     * at the end so the caller keeps trading blocked until every exchange is reachable again
     * (safe default — the global flag has no per-account granularity).
     */
    public void reconcileAll() {
        List<String> failed = new ArrayList<>();
        for (AccountReconciler r : reconcilers.all().values()) {
            try {
                reconcileAllForAccount(r);
            } catch (RuntimeException e) {
                failed.add(r.accountId());
                log.error("[RECON] account {} full cycle FAILED: {}", r.accountId(), e.getMessage(), e);
            }
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException("reconciliation failed for account(s) " + failed);
        }
    }

    /**
     * One account's cycle: bulk positions read, per-symbol reconcile, then equity/balance. One bad
     * symbol is logged and skipped so it can't abort the account's cycle. Throws if the bulk
     * positions read itself fails.
     */
    void reconcileAllForAccount(AccountReconciler r) {
        List<ExchangePosition> exPositions = r.positions(null);

        for (String symbol : symbolsToReconcile(r.accountId(), exPositions)) {
            try {
                Optional<ExchangePosition> exPos = pickPosition(exPositions, symbol);
                List<ExchangeOrderStatus> orders = fetchOrderStatuses(r, symbol);
                symbolReconciler.reconcileSymbol(r.accountId(), r.exchange(), symbol, exPos, orders);
            } catch (RuntimeException e) {
                // A symbol that cannot reconcile must not keep trading on unverified state: the
                // whole cycle still "succeeded" before, so reconciliationInSync stayed true and a
                // persistently failing symbol (broken getOrder/parse) traded blind. Mark it
                // out-of-sync — its entries block, other symbols/accounts are unaffected; a later
                // successful reconcile clears the mark.
                healthService.markSymbolOutOfSync(r.accountId(), symbol);
                log.error("[RECON] account {} symbol {} failed this cycle (entries blocked, will retry): {}",
                        r.accountId(), symbol, e.getMessage(), e);
            }
        }

        // Balance/equity is for PnL/drawdown, not position safety — a transient read failure here must
        // not block trading once positions have reconciled. Log and move on; next cycle retries.
        try {
            reconcileBalanceAndEquity(r);
        } catch (RuntimeException e) {
            log.warn("[RECON] account {} balance/equity sample failed this cycle (positions already reconciled): {}",
                    r.accountId(), e.getMessage());
        }
    }

    // ---- Single symbol (post-order trigger) ----

    /**
     * Reconcile one symbol on every account immediately after an order action (place/fill/cancel).
     * Per-account failures are logged and skipped — the periodic cycle is the backstop.
     */
    public void reconcileSymbol(String coreSymbol) {
        String sym = coreSymbol.toUpperCase(Locale.ROOT);
        for (AccountReconciler r : reconcilers.all().values()) {
            try {
                Optional<ExchangePosition> exPos = pickPosition(r.positions(sym), sym);
                List<ExchangeOrderStatus> orders = fetchOrderStatuses(r, sym);
                symbolReconciler.reconcileSymbol(r.accountId(), r.exchange(), sym, exPos, orders);
            } catch (RuntimeException e) {
                log.warn("[RECON] account {} post-order reconcile failed for {}: {}",
                        r.accountId(), sym, e.getMessage());
            }
        }
    }

    // ---- FU-3: equity / balance from the exchange (ADR-0017) ----

    void reconcileBalanceAndEquity(AccountReconciler r) {
        Optional<ExchangeBalance> balOpt = r.balance();
        if (balOpt.isEmpty()) {
            log.warn("[RECON] account {} — no balance row in exchange response", r.accountId());
            return;
        }
        ExchangeBalance bal = balOpt.get();

        BigDecimal equity = computeEquity(bal);
        pnlTracker.sampleEquity(r.accountId(), equity);

        BigDecimal total = bal.balance() != null ? bal.balance() : bal.equity();
        if (total != null) {
            // Floating PnL and equity ride along so consumers report the venue's own numbers
            // (ADR-0017) instead of re-deriving them from per-position snapshots.
            stateProducer.publishBalanceUpdated(
                    r.accountId(), r.exchange(), bal.asset(),
                    bal.available(), bal.used(), total,
                    bal.unrealizedPnl(), equity, null, null);
        }
        log.debug("[RECON] account {} equity sampled asset={} balance={} unrealized={} equity={}",
                r.accountId(), bal.asset(), bal.balance(), bal.unrealizedPnl(), equity);
    }

    /** Equity = wallet balance + unrealized PnL (ADR-0017); falls back to the venue's equity field. */
    private BigDecimal computeEquity(ExchangeBalance bal) {
        if (bal.balance() != null) {
            BigDecimal u = bal.unrealizedPnl() != null ? bal.unrealizedPnl() : BigDecimal.ZERO;
            return bal.balance().add(u);
        }
        return bal.equity();
    }

    // ---- Helpers ----

    /** Union of: open exchange positions, the account's open local positions, and its locally-active orders' symbols. */
    private TreeSet<String> symbolsToReconcile(String accountId, List<ExchangePosition> exPositions) {
        TreeSet<String> symbols = new TreeSet<>();
        for (ExchangePosition p : exPositions) {
            if (!p.isFlat()) {
                symbols.add(p.symbol());
            }
        }
        for (PositionEntity p : positionRepository.findAllOpenByAccountId(accountId)) {
            symbols.add(p.getSymbol().toUpperCase(Locale.ROOT));
        }
        for (OrderEntity o : orderRepository.findAllActiveByAccountId(accountId)) {
            symbols.add(o.getSymbol().toUpperCase(Locale.ROOT));
        }
        return symbols;
    }

    private Optional<ExchangePosition> pickPosition(List<ExchangePosition> positions, String coreSymbol) {
        return positions.stream()
                .filter(p -> coreSymbol.equals(p.symbol()))
                .reduce((a, b) -> a.isFlat() ? b : a);
    }

    /** Exchange status of each of the account's locally-active orders for a symbol; failed reads are skipped. */
    private List<ExchangeOrderStatus> fetchOrderStatuses(AccountReconciler r, String coreSymbol) {
        List<OrderEntity> active = orderRepository.findActiveByAccountIdAndSymbol(r.accountId(), coreSymbol);
        if (active.isEmpty()) {
            return List.of();
        }
        List<ExchangeOrderStatus> out = new ArrayList<>(active.size());
        for (OrderEntity o : active) {
            ExchangeOrderStatus st = r.orderStatus(o);
            if (st != null) {
                out.add(st);
            }
        }
        return out;
    }
}
