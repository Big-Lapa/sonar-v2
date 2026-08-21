package com.sonar.trading.reconciliation;

import com.sonar.trading.domain.entity.FillEntity;
import com.sonar.trading.domain.entity.OrderEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.repository.CooldownRepository;
import com.sonar.trading.domain.repository.FillRepository;
import com.sonar.trading.domain.repository.OrderRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.exchange.model.AdapterOrderState;
import com.sonar.trading.health.SystemHealthService;
import com.sonar.trading.messaging.producer.ReconciliationEventsProducer;
import com.sonar.trading.pnl.DailyPnlTracker;
import com.sonar.trading.messaging.producer.TradingStateProducer;
import com.sonar.trading.reconciliation.model.ExchangeOrderStatus;
import com.sonar.trading.reconciliation.model.ExchangePosition;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transactional core of reconciliation (T17): given the exchange's authoritative view (already
 * fetched, no I/O here), reconcile one symbol's local position and orders, overwriting only
 * <b>runtime</b> fields. The exchange is the source of truth; local state is never pushed back.
 *
 * <p>Safe-window per symbol: on any detected mismatch the symbol is marked out-of-sync (new entries
 * blocked via {@link SystemHealthService}) <em>before</em> mutation, then overwritten with the
 * pulled truth, then cleared only once local matches again. If the transaction rolls back, the mark
 * persists, so a symbol that cannot converge stays blocked — the safe default.
 *
 * <p>Never touches intent/audit: {@code positionId}, {@code command_id}, {@code client_order_id},
 * {@code signal_id}, correlationId, realized PnL history, or the order.commands log.
 *
 * <p>A position whose structural fields already agree is still marked to market — see
 * {@link #refreshRuntime} — so {@code markPrice}/{@code unrealizedPnl} track the exchange rather
 * than freezing at their open-time values. That path is silent: it is not a mismatch.
 *
 * <p>Per-account since P4: the caller passes the {@code accountId}/{@code exchange} of the account
 * being reconciled; positions are keyed {@code (accountId, symbol)} (P2), and the out-of-sync mark
 * is keyed {@code (accountId, symbol)} too — one account's divergence blocks only its own entries
 * on the symbol, healthy accounts keep trading it.
 *
 * <p>Every resolved mismatch also emits a {@code reconciliation.events} audit event (H4,
 * ADR-0027) through the outbox, in the same transaction — see {@link #safeEmit(Runnable)} for
 * the failure policy. The local-state snapshot is captured <em>before</em> mutation.
 */
@Component
public class SymbolReconciler {

    private static final Logger log = LoggerFactory.getLogger(SymbolReconciler.class);

    static final String MISMATCH_METRIC = "position_reconciliation_mismatch_total";
    static final String EMIT_FAILED_METRIC = "reconciliation_event_emit_failed_total";
    static final String TYPE_MISSING_LOCAL = "missing_local";
    static final String TYPE_MISSING_EXCHANGE = "missing_exchange";
    static final String TYPE_FIELD_MISMATCH = "field_mismatch";
    static final String TYPE_ORDER_STATUS = "order_status";

    private static final Set<String> LOCAL_TERMINAL =
            Set.of("FILLED", "REJECTED", "CANCELED", "CANCELLED", "FAILED");

    /** FU-8: per-(account,symbol) locks prevent concurrent reconcile of the same key from flapping mark/clear. */
    private final ConcurrentHashMap<String, Object> symbolLocks = new ConcurrentHashMap<>();

    private final PositionRepository positionRepository;
    private final OrderRepository orderRepository;
    private final FillRepository fillRepository;
    private final TradingStateProducer stateProducer;
    private final ReconciliationEventsProducer reconEventsProducer;
    private final SystemHealthService healthService;
    private final MeterRegistry meterRegistry;
    private final ReconciliationProperties props;
    private final DailyPnlTracker pnlTracker;
    private final CooldownRepository cooldownRepository;

    public SymbolReconciler(PositionRepository positionRepository,
                            OrderRepository orderRepository,
                            FillRepository fillRepository,
                            TradingStateProducer stateProducer,
                            ReconciliationEventsProducer reconEventsProducer,
                            SystemHealthService healthService,
                            MeterRegistry meterRegistry,
                            ReconciliationProperties props,
                            DailyPnlTracker pnlTracker,
                            CooldownRepository cooldownRepository) {
        this.positionRepository = positionRepository;
        this.orderRepository = orderRepository;
        this.fillRepository = fillRepository;
        this.stateProducer = stateProducer;
        this.reconEventsProducer = reconEventsProducer;
        this.healthService = healthService;
        this.meterRegistry = meterRegistry;
        this.props = props;
        this.pnlTracker = pnlTracker;
        this.cooldownRepository = cooldownRepository;
    }

    /**
     * Reconcile one account's symbol against the exchange truth. {@code exPos} is the exchange
     * position (empty or flat means no open position); {@code orderStatuses} are exchange-reported
     * statuses for the account's locally-active orders on the symbol. All inputs are pre-fetched —
     * this method performs no network I/O.
     */
    @Transactional
    public void reconcileSymbol(String accountId,
                                String exchange,
                                String coreSymbol,
                                Optional<ExchangePosition> exPos,
                                List<ExchangeOrderStatus> orderStatuses) {
        String sym = coreSymbol.toUpperCase(java.util.Locale.ROOT);
        // FU-8: serialize concurrent reconcile of the same (account,symbol) (post-order vs periodic)
        // to prevent mark/clear interleaving. Other accounts/symbols are unaffected.
        synchronized (symbolLocks.computeIfAbsent(accountId + "|" + sym, k -> new Object())) {
            reconcileSymbolLocked(accountId, exchange, sym, exPos, orderStatuses);
        }
    }

    private void reconcileSymbolLocked(String accountId,
                                       String exchange,
                                       String sym,
                                       Optional<ExchangePosition> exPos,
                                       List<ExchangeOrderStatus> orderStatuses) {
        // 1. Orders first — a missed fill should be recorded before we judge the position.
        reconcileOrders(accountId, exchange, sym, orderStatuses);

        // 2. Position.
        Optional<PositionEntity> localOpt = positionRepository.findByAccountIdAndSymbol(accountId, sym);
        boolean localOpen = localOpt.map(SymbolReconciler::isOpen).orElse(false);
        ExchangePosition ex = exPos.filter(p -> !p.isFlat()).orElse(null);
        boolean exOpen = ex != null;

        if (!localOpen && !exOpen) {
            healthService.clearSymbolOutOfSync(accountId, sym);   // both flat → in sync; no DB write, clear now
            return;
        }

        if (!localOpen) {
            adopt(localOpt, accountId, exchange, sym, ex);    // case 1: missing_local
        } else if (!exOpen) {
            markClosed(localOpt.get(), accountId, sym);      // case 2: missing_exchange
        } else if (matches(localOpt.get(), ex)) {
            refreshRuntime(localOpt.get(), ex);                   // both open and equal → mark-to-market only
            healthService.clearSymbolOutOfSync(accountId, sym);
        } else {
            overwrite(localOpt.get(), accountId, sym, ex);   // case 3: field_mismatch
        }
    }

    // ---- Position mismatch handlers ----

    /** Case 1: a position exists on the exchange but not locally (missed fill) — adopt it. */
    private void adopt(Optional<PositionEntity> localOpt, String accountId, String exchange,
                       String sym, ExchangePosition ex) {
        beginSafeWindow(accountId, sym, TYPE_MISSING_LOCAL,
                "position on exchange but not local (missed fill): {} {} qty={} entry={}",
                sym, ex.side(), ex.qty(), ex.avgPrice());
        safeEmit(() -> reconEventsProducer.publishAdopted(accountId, exchange, sym, ex));
        PositionEntity pos = localOpt.orElseGet(() ->
                PositionEntity.newFlat(accountId, exchange, sym));
        pos.setRealizedPnl(BigDecimal.ZERO);   // adopt = new trade cycle; previous cycle's PnL must not leak
        applyExchangeRuntime(pos, ex);
        positionRepository.save(pos);
        emit(pos);
        clearAfterCommit(accountId, sym);  // FU-6: defer until tx commits; on rollback symbol stays blocked (safe)
    }

    /** Case 2: a position exists locally but not on the exchange (closed externally / liquidation). */
    private void markClosed(PositionEntity pos, String accountId, String sym) {
        beginSafeWindow(accountId, sym, TYPE_MISSING_EXCHANGE,
                "position local but gone on exchange (closed externally/liquidation): {} {} qty={}",
                sym, pos.getSide(), pos.getQuantity());
        safeEmit(() -> reconEventsProducer.publishMarkedClosed(accountId, pos.getExchange(), sym, pos));
        long now = System.currentTimeMillis();
        // The exchange no longer reports the position, so the exact realized PnL of the close is
        // unknown here. Fold the last mark-to-market unrealizedPnl (venue-sourced, at most
        // runtime-refresh-ms stale) into realizedPnl so the CLOSED trading.state event carries a
        // best-effort figure instead of the row's initial 0 — time-stop and external closes all
        // land in this path.
        if (pos.getUnrealizedPnl() != null) {
            BigDecimal prior = pos.getRealizedPnl() != null ? pos.getRealizedPnl() : BigDecimal.ZERO;
            BigDecimal delta = pos.getUnrealizedPnl();
            pos.setRealizedPnl(prior.add(delta));
            // Exchange-side closes (venue TP/SL, liquidation, external close-all, time-stop) never
            // pass through Tx2, so without this the daily-loss limit and the post-loss cooldown are
            // blind to exactly the stop-out class of exits. Same-transaction as the position write.
            pnlTracker.addRealizedPnl(accountId, delta);
            if (delta.signum() < 0) {
                cooldownRepository.upsert(accountId, sym, now);
            }
        }
        pos.setSide("FLAT");
        pos.setQuantity(BigDecimal.ZERO);
        pos.setEntryPrice(null);
        pos.setMarkPrice(null);
        pos.setUnrealizedPnl(null);
        pos.setLiquidationPrice(null);
        pos.setStatus("CLOSED");
        pos.setOpenedAt(null);
        pos.setUpdatedAt(now);
        positionRepository.save(pos);
        emit(pos);
        clearAfterCommit(accountId, sym);  // FU-6: defer until tx commits
    }

    /** Case 3: both present but runtime fields diverge — overwrite local with exchange values. */
    private void overwrite(PositionEntity pos, String accountId, String sym, ExchangePosition ex) {
        beginSafeWindow(accountId, sym, TYPE_FIELD_MISMATCH,
                "position field mismatch {}: local side={} qty={} entry={} lev={} | exchange side={} qty={} entry={} lev={}",
                sym, pos.getSide(), pos.getQuantity(), pos.getEntryPrice(), pos.getLeverage(),
                ex.side(), ex.qty(), ex.avgPrice(), ex.leverage());
        safeEmit(() -> reconEventsProducer.publishOverwritten(accountId, pos.getExchange(), sym, pos, ex));
        applyExchangeRuntime(pos, ex);
        positionRepository.save(pos);
        emit(pos);
        clearAfterCommit(accountId, sym);  // FU-6: defer until tx commits
    }

    /** Overwrite runtime fields only. Identity ({@code positionId}, exchange, symbol) and realized PnL are preserved. */
    private void applyExchangeRuntime(PositionEntity pos, ExchangePosition ex) {
        long now = System.currentTimeMillis();
        pos.setSide(ex.side());
        pos.setQuantity(ex.qty() != null ? ex.qty() : BigDecimal.ZERO);
        pos.setEntryPrice(ex.avgPrice());
        pos.setMarkPrice(ex.markPrice());
        pos.setUnrealizedPnl(ex.unrealizedPnl());
        if (ex.leverage() != null) {
            pos.setLeverage(ex.leverage());
        }
        pos.setLiquidationPrice(ex.liquidationPrice());
        pos.setStatus("OPEN");
        if (pos.getOpenedAt() == null) {
            pos.setOpenedAt(now);
        }
        pos.setUpdatedAt(now);
    }

    /**
     * Mark-to-market of an already in-sync position: copy the exchange's runtime-only fields
     * ({@code markPrice}, {@code unrealizedPnl}, {@code liquidationPrice}, {@code leverage}) onto
     * the local row. Structural fields are left alone — {@link #matches} has just proven they agree.
     *
     * <p>Deliberately silent: a moving mark price is not a divergence. No safe window, no
     * {@code reconciliation.events} audit record, no mismatch counter, no WARN. Without this,
     * {@code unrealizedPnl} was only ever written by {@link #adopt} / {@link #overwrite}, so a
     * position that opened cleanly kept its open-time floating PnL (≈ minus the spread and fee)
     * for its whole life, and {@code /status} reported a loss while the exchange showed a profit.
     *
     * <p>Throttled by {@code trading.reconciliation.runtime-refresh-ms}: recon runs every
     * {@code interval-ms}, and a row write plus a {@code trading.state} event per open position per
     * cycle is noise. Position status does not change here, so no operator notification is emitted.
     */
    private void refreshRuntime(PositionEntity pos, ExchangePosition ex) {
        if (System.currentTimeMillis() - pos.getUpdatedAt() < props.getRuntimeRefreshMs()
                || !runtimeDiffers(pos, ex)) {
            return;
        }
        pos.setMarkPrice(ex.markPrice());
        pos.setUnrealizedPnl(ex.unrealizedPnl());
        pos.setLiquidationPrice(ex.liquidationPrice());
        if (ex.leverage() != null) {
            pos.setLeverage(ex.leverage());
        }
        pos.setUpdatedAt(System.currentTimeMillis());
        positionRepository.save(pos);
        emit(pos);
    }

    /** True when the exchange's runtime view has moved away from the stored one. */
    private boolean runtimeDiffers(PositionEntity pos, ExchangePosition ex) {
        return !approxEqual(pos.getMarkPrice(), ex.markPrice(), props.getPriceEpsilon())
                || !approxEqual(pos.getUnrealizedPnl(), ex.unrealizedPnl(), props.getPriceEpsilon())
                || !approxEqual(pos.getLiquidationPrice(), ex.liquidationPrice(), props.getPriceEpsilon());
    }

    private boolean matches(PositionEntity local, ExchangePosition ex) {
        boolean sameSide = local.getSide() != null && local.getSide().equals(ex.side());
        boolean sameQty = approxEqual(local.getQuantity(), ex.qty(), props.getQtyEpsilon());
        boolean samePrice = approxEqual(local.getEntryPrice(), ex.avgPrice(), props.getPriceEpsilon());
        // Leverage is exchange-owned runtime info the local fill path never sets: comparing it
        // made every OWN fill a "mismatch" (local lev=null vs exchange 50) — WARN spam plus a
        // needless safe-window flap per position. It is still copied by applyExchangeRuntime
        // whenever a real divergence triggers an overwrite.
        return sameSide && sameQty && samePrice;
    }

    // ---- Order mismatch (case 4) ----

    private void reconcileOrders(String accountId, String exchange, String sym,
                                 List<ExchangeOrderStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ExchangeOrderStatus st : statuses) {
            Optional<OrderEntity> oOpt = orderRepository.findByClientOrderId(st.clientOrderId());
            if (oOpt.isEmpty()) {
                continue; // unknown order — we never adopt arbitrary exchange orders into our audit log
            }
            OrderEntity o = oOpt.get();
            if (isLocalTerminal(o.getStatus()) || !st.isTerminal()) {
                continue; // already settled locally, or exchange not terminal yet — nothing to do
            }
            String newStatus = st.state().name();
            String localStatusBefore = o.getStatus();
            log.warn("[RECON] order status mismatch {} cid={}: local={} -> exchange={} filled={} avg={}",
                    sym, st.clientOrderId(), o.getStatus(), newStatus, st.filledQty(), st.avgPrice());
            safeEmit(() -> reconEventsProducer.publishOrderSynced(
                    accountId, exchange, sym, st.clientOrderId(), localStatusBefore, newStatus));
            o.applyResult(
                    st.exchangeOrderId() != null ? st.exchangeOrderId() : o.getExchangeOrderId(),
                    newStatus, st.filledQty(), st.avgPrice(), st.fee(), null, null, now);
            orderRepository.save(o);
            mismatch(TYPE_ORDER_STATUS);

            boolean filled = st.state() == AdapterOrderState.FILLED
                    && st.filledQty() != null && st.filledQty().signum() > 0;
            // FU-7: BingX getOrder returns only aggregate executedQty + avgPrice — no per-fill
            // breakdown is available in the API response, so we write one FillEntity per order.
            // Per-fill rows would require a trade history endpoint not used in the current design.
            if (filled && o.getId() != null && !fillRepository.existsByOrderId(o.getId())) {
                fillRepository.save(new FillEntity(
                        o.getId(), o.getAccountId(), sym, st.filledQty(),
                        st.avgPrice() != null ? st.avgPrice() : BigDecimal.ZERO, st.fee(), now));
            }
        }
    }

    // ---- Helpers ----

    /**
     * FU-6: clear the per-symbol out-of-sync flag only after the current transaction commits.
     * If the transaction rolls back, the flag is never cleared — the symbol stays blocked (safe default).
     * Falls back to an immediate clear when called outside a transaction (e.g. unit tests).
     */
    private void clearAfterCommit(String accountId, String sym) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    healthService.clearSymbolOutOfSync(accountId, sym);
                }
            });
        } else {
            healthService.clearSymbolOutOfSync(accountId, sym);
        }
    }

    /**
     * H4/ADR-0027: reconciliation.events are telemetry — recon must never fail because of them.
     * The outbox INSERT shares the recon transaction (atomic with the fix when it works), but a
     * producer-side failure (e.g. serialization bug) is swallowed: WARN + counter, event lost,
     * position recovery proceeds. A real DB outage fails the recon transaction on its own anyway.
     */
    private void safeEmit(Runnable emission) {
        try {
            emission.run();
        } catch (RuntimeException e) {
            meterRegistry.counter(EMIT_FAILED_METRIC).increment();
            log.warn("[RECON] reconciliation.events emission failed (recon continues): {}", e.getMessage(), e);
        }
    }

    private void beginSafeWindow(String accountId, String sym, String type, String msg, Object... args) {
        healthService.markSymbolOutOfSync(accountId, sym);
        log.warn("[RECON] " + msg, args);
        mismatch(type);
    }

    private void mismatch(String type) {
        meterRegistry.counter(MISMATCH_METRIC, "type", type).increment();
    }

    private void emit(PositionEntity pos) {
        stateProducer.publishPositionUpdated(pos, null, null);
    }

    private static boolean isOpen(PositionEntity p) {
        return "OPEN".equals(p.getStatus()) && p.getQuantity() != null && p.getQuantity().signum() > 0;
    }

    private static boolean isLocalTerminal(String status) {
        return status != null && LOCAL_TERMINAL.contains(status.toUpperCase(java.util.Locale.ROOT));
    }

    private static boolean approxEqual(BigDecimal a, BigDecimal b, BigDecimal eps) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.subtract(b).abs().compareTo(eps) <= 0;
    }
}
