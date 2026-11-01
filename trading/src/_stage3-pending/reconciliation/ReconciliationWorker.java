package com.sonar.trading.reconciliation;

import com.sonar.trading.config.AccountProps;
import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.config.TradingProperties;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.health.SystemHealthService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drives reconciliation (T17, per-account since P4). Active whenever at least one
 * {@link AccountReconciler} is registered — any BINGX/BINANCE account in the registry, or the
 * legacy single-account LIVE mode. PAPER-only setups register no reconcilers: the worker stays
 * idle and never flips the health flag, so paper trading is untouched. Triggers:
 * <ul>
 *   <li><b>on-startup</b> — runs before signals are trusted; until the first cycle succeeds the
 *       global reconciliation flag is {@code false}, so the health gate blocks all new entries
 *       (closes/reduces stay exempt). Safe default: trade only once we've matched the exchange.</li>
 *   <li><b>periodic</b> — adaptive: {@code trading.reconciliation.interval-ms} (5s) while at least
 *       one position is open, otherwise {@code trading.reconciliation.idle-interval-ms} (60s).</li>
 *   <li><b>post-order</b> — after an order action settles (handled after the producing tx commits).</li>
 * </ul>
 * Per-symbol mismatches are handled inside the cycle (per-symbol block). The global flag is flipped
 * only by whole-cycle outcome: a cycle that cannot reach every exchange blocks all new entries.
 *
 * <p>Gap/reconnect and pre-open triggers are deferred follow-ups.
 */
@Component
public class ReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationWorker.class);

    private final ReconciliationService service;
    private final SystemHealthService healthService;
    private final AccountReconcilers reconcilers;
    private final AccountRegistry registry;
    private final TradingProperties tradingProps;
    private final ReconciliationProperties reconProps;
    private final PositionRepository positionRepository;

    /** Moment of the last periodic cycle — the basis for idle cadence. */
    private volatile long lastPeriodicRunMs;

    public ReconciliationWorker(ReconciliationService service,
                                SystemHealthService healthService,
                                AccountReconcilers reconcilers,
                                AccountRegistry registry,
                                TradingProperties tradingProps,
                                ReconciliationProperties reconProps,
                                PositionRepository positionRepository) {
        this.service = service;
        this.healthService = healthService;
        this.reconcilers = reconcilers;
        this.registry = registry;
        this.tradingProps = tradingProps;
        this.reconProps = reconProps;
        this.positionRepository = positionRepository;
    }

    /**
     * Block all new entries until the first successful reconciliation (startup or first tick).
     * Decided from configuration (not from {@link AccountReconcilers}, whose registrations may not
     * have happened yet at this point): reconciliation is expected when any enabled account is a
     * real venue, or when legacy mode runs {@code trading.exchange=LIVE}.
     */
    @PostConstruct
    void blockUntilFirstSync() {
        if (!reconciliationExpected()) {
            log.info("[RECON] no live exchange accounts — reconciliation worker idle");
            return;
        }
        healthService.setReconciliationInSync(false);
        log.info("[RECON] reconciliation worker armed — new entries blocked until first sync");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (reconcilers.isEmpty()) {
            return;
        }
        log.info("[RECON] startup reconciliation before accepting signals...");
        runCycle("startup");
    }

    /**
     * Periodic cycle with adaptive cadence. The scheduler ticks with a step of
     * {@code trading.reconciliation.interval-ms}, but while there are no open positions the cycle actually
     * runs no more often than {@code idle-interval-ms} — the other ticks are skipped.
     *
     * <p>Why this way: a position exits via exchange SL/TP, i.e. the exchange closes the position without
     * our order. The only one who notices this is recon, so while a position is open frequent
     * reconciliation is needed (delay = delay of realized PnL, CLOSED and freeing the symbol). When flat
     * there is nothing to reconcile, and polling the exchange every 5s is pure traffic.
     *
     * <p>The open-position count is read from the local DB, not from the exchange, so the gate itself is free.
     * A DB read error is treated in favor of the frequent cycle (safe default: better an extra poll than a
     * missed close).
     */
    @Scheduled(fixedDelayString = "${trading.reconciliation.interval-ms:5000}")
    public void onSchedule() {
        if (reconcilers.isEmpty()) {
            return;
        }
        if (!periodicCycleDue()) {
            return;
        }
        lastPeriodicRunMs = System.currentTimeMillis();
        runCycle("periodic");
    }

    /** The tick runs if there are open positions, or the idle window has elapsed since the last cycle. */
    private boolean periodicCycleDue() {
        if (hasOpenPositions()) {
            return true;
        }
        long idleMs = reconProps.getIdleIntervalMs();
        if (idleMs <= reconProps.getIntervalMs()) {
            return true; // idle cadence is disabled/meaningless — behave as before
        }
        return System.currentTimeMillis() - lastPeriodicRunMs >= idleMs;
    }

    private boolean hasOpenPositions() {
        try {
            return positionRepository.countOpen() > 0;
        } catch (RuntimeException e) {
            log.warn("[RECON] open-position probe failed ({}) — keeping the frequent cadence", e.getMessage());
            return true;
        }
    }

    /**
     * Post-order trigger: reconciles the just-touched symbol right after an order settles.
     *
     * <p>{@code fallbackExecution = true} is required: {@code SignalProcessor.process()} is
     * deliberately NOT {@code @Transactional} (its DB work runs in short self-managed transactions
     * via {@code TradingTransactionService} that commit before the trigger is published), so the
     * event is fired with no transaction bound to the thread. Without the fallback, an
     * {@code AFTER_COMMIT} listener is silently discarded in that case and this fast-path never runs
     * — leaving only the periodic sweep. Failures are swallowed — the periodic cycle is the backstop.
     *
     * <p>{@code @Async} moves the work off the signal thread. The trigger is published with no
     * transaction bound (see above), so the {@code fallbackExecution} listener would otherwise run
     * <em>inline inside {@code publishEvent()}</em>, on the signal consumer / fan-out thread, and
     * {@code reconcileSymbol} walks every registered account with REST calls — so N accounts fanning
     * out N triggers cost ~N² exchange round-trips on the signal path. Dispatching on
     * {@link AsyncReconciliationConfig#EXECUTOR} keeps those round-trips off the fast path; the 5s
     * periodic sweep remains the backstop and exceptions are caught here, so nothing reaches the
     * consumer/DLQ.
     */
    @Async(AsyncReconciliationConfig.EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOrderSettled(ReconciliationTrigger trigger) {
        if (reconcilers.isEmpty()) {
            return;
        }
        try {
            log.debug("[RECON] post-order reconcile for {}", trigger.symbol());
            service.reconcileSymbol(trigger.symbol());
        } catch (RuntimeException e) {
            log.warn("[RECON] post-order reconcile failed for {}: {}", trigger.symbol(), e.getMessage());
        }
    }

    /**
     * Run a full cycle and flip the global sync flag by outcome: success → in-sync (entries allowed,
     * subject to per-symbol blocks); failure to reach an exchange → out-of-sync (all new entries
     * blocked, safe default).
     */
    private void runCycle(String trigger) {
        try {
            service.reconcileAll();
            if (!healthService.isReconciliationInSync()) {
                log.info("[RECON] {} cycle ok — reconciliation back in sync, entries allowed", trigger);
            }
            healthService.setReconciliationInSync(true);
        } catch (RuntimeException e) {
            healthService.setReconciliationInSync(false);
            log.error("[RECON] {} cycle FAILED — exchange unreachable, new entries blocked: {}",
                    trigger, e.getMessage(), e);
        }
    }

    /** True when the config implies reconcilers will be registered (must match the factory's rules). */
    private boolean reconciliationExpected() {
        if (!registry.isEmpty()) {
            return registry.enabledAccounts().stream().anyMatch(AccountProps::requiresCredentials);
        }
        return "LIVE".equalsIgnoreCase(tradingProps.getExchange());
    }
}
