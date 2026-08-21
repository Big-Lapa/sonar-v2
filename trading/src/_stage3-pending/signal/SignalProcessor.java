package com.sonar.trading.signal;

import com.sonar.trading.config.AccountContext;
import com.sonar.trading.config.AccountOverride;
import com.sonar.trading.config.AccountProps;
import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.config.ConfigCache;
import com.sonar.trading.config.OnDemandSymbolRuleClient;
import com.sonar.trading.config.RiskProfile;
import com.sonar.trading.config.SymbolRules;
import com.sonar.trading.config.TradingProperties;
import com.sonar.trading.domain.entity.OrderCommandEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.repository.*;
import com.sonar.trading.exchange.dto.AdapterOrderResult;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.AccountExecutionAdapters;
import com.sonar.trading.execution.SignalExecutionPort;
import com.sonar.trading.execution.PriceFeed;
import com.sonar.trading.health.SystemHealthService;
import com.sonar.trading.signal.dto.TradingSignalPayload;
import com.sonar.trading.messaging.producer.OrderResultProducer;
import com.sonar.trading.pnl.DailyPnlTracker;
import com.sonar.trading.reconciliation.ReconciliationTrigger;
import com.sonar.trading.risk.RiskContext;
import com.sonar.trading.risk.RiskDecision;
import com.sonar.trading.risk.RiskEngine;
import com.sonar.trading.sizing.PositionSizer;
import com.sonar.trading.sizing.QuantityNormalizer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates the full signal → risk → execution → state pipeline.
 *
 * <p>After platform-hardening (Part B): {@code process()} is NOT @Transactional.
 * DB writes are split across two short transactions managed by {@link TradingTransactionService}:
 * <ul>
 *   <li>Tx1 ({@link TradingTransactionService#savePendingSubmitCommand}): dedup + PENDING_SUBMIT command
 *       + outbox(order.commands) — committed BEFORE the exchange HTTP call.
 *   <li>Tx2 ({@link TradingTransactionService#executeTx2}): order + cmd status + position + PnL + cooldown
 *       + outbox(results) — committed AFTER the exchange HTTP call returns.
 * </ul>
 * This eliminates the "exchange HTTP inside a held transaction" anti-pattern and makes crash
 * recovery possible via {@link PendingSubmitRecoveryService}.
 */
@Service
public class SignalProcessor {

    private static final Logger log = LoggerFactory.getLogger(SignalProcessor.class);

    private final ConfigCache configCache;
    private final PositionRepository positionRepository;
    private final CooldownRepository cooldownRepository;
    private final RiskEngine riskEngine;
    private final PositionSizer positionSizer;
    private final QuantityNormalizer quantityNormalizer;
    private final SignalExecutionPort executionEngine;
    private final PriceFeed priceCache;
    private final DailyPnlTracker pnlTracker;
    private final SystemHealthService healthService;
    private final OrderResultProducer orderResultProducer;
    private final ApplicationEventPublisher eventPublisher;
    private final TradingProperties props;
    private final MeterRegistry meterRegistry;
    private final TradingTransactionService txService;
    private final AccountContext accountContext;
    private final AccountExecutionAdapters accountAdapters;
    private final AccountRegistry accountRegistry;
    private final ExecutionAdapter primaryAdapter;
    /** Nullable (tests): realized-slippage metric, fails soft when absent. */
    private final OrderSlippageRecorder slippageRecorder;
    /** Nullable (tests): on-demand symbol_rules lookup, fails soft when absent (behaves like a
     *  permanently-empty registry — same as before ADR-0032). */
    private final OnDemandSymbolRuleClient onDemandSymbolRuleClient;

    /**
     * Bounded pool for parallel fan-out across accounts (P3). Fixed size =
     * {@code signalConcurrency × max(1, accounts)} (min 2): with N consumer threads each fanning
     * out to A accounts, up to N×A account-tasks run at once, so a smaller pool would queue tasks
     * and trip the fan-out timeout spuriously. A cachedThreadPool would instead spawn unbounded
     * threads under a backlog. Sized once in the constructor.
     */
    private final ExecutorService fanoutExecutor;

    /** Cap on how long the whole fan-out may take before the consumer thread is released
     *  ({@code trading.fanout-timeout-ms}, default 30s). Sourced from config so tests can shrink it. */
    private final long fanoutTimeoutMs;

    public SignalProcessor(ConfigCache configCache,
                            PositionRepository positionRepository,
                            CooldownRepository cooldownRepository,
                            RiskEngine riskEngine,
                            PositionSizer positionSizer,
                            QuantityNormalizer quantityNormalizer,
                            SignalExecutionPort executionEngine,
                            PriceFeed priceCache,
                            DailyPnlTracker pnlTracker,
                            SystemHealthService healthService,
                            OrderResultProducer orderResultProducer,
                            ApplicationEventPublisher eventPublisher,
                            TradingProperties props,
                            MeterRegistry meterRegistry,
                            TradingTransactionService txService,
                            AccountContext accountContext,
                            AccountExecutionAdapters accountAdapters,
                            AccountRegistry accountRegistry,
                            ExecutionAdapter primaryAdapter,
                            OrderSlippageRecorder slippageRecorder,
                            OnDemandSymbolRuleClient onDemandSymbolRuleClient,
                            @Value("${trading.signals.concurrency:6}") int signalConcurrency) {
        this.configCache = configCache;
        this.positionRepository = positionRepository;
        this.cooldownRepository = cooldownRepository;
        this.riskEngine = riskEngine;
        this.positionSizer = positionSizer;
        this.quantityNormalizer = quantityNormalizer;
        this.executionEngine = executionEngine;
        this.priceCache = priceCache;
        this.pnlTracker = pnlTracker;
        this.healthService = healthService;
        this.orderResultProducer = orderResultProducer;
        this.eventPublisher = eventPublisher;
        this.props = props;
        this.meterRegistry = meterRegistry;
        this.txService = txService;
        this.accountContext = accountContext;
        this.accountAdapters = accountAdapters;
        this.accountRegistry = accountRegistry;
        this.primaryAdapter = primaryAdapter;
        this.slippageRecorder = slippageRecorder;
        this.onDemandSymbolRuleClient = onDemandSymbolRuleClient;
        this.fanoutTimeoutMs = props.getFanoutTimeoutMs();
        int accounts = Math.max(1, accountRegistry.enabledAccounts().size());
        int poolSize = Math.max(2, Math.max(1, signalConcurrency) * accounts);
        this.fanoutExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "signal-fanout");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        fanoutExecutor.shutdown();
    }

    /** One fan-out target: an account, its venue label, notional override and execution adapter. */
    private record AccountTarget(String accountId, String exchange, BigDecimal notionalOverride,
                                 ExecutionAdapter adapter) {}

    /** Venue без реестра контрактов — проверять «торгуется ли символ» там не по чему. */
    private static final java.util.Set<String> VENUES_WITHOUT_CONTRACTS = java.util.Set.of("PAPER", "BACKTEST");

    /**
     * Биржа исполнения выбирается ОДНА (не веер по всем аккаунтам): по списку
     * {@code trading.venue-priority} берётся первый аккаунт, у которого символ действительно
     * торгуется. Одна монета не открывается на двух биржах разом —
     * сравнение venue по костам закрыто, а двойная экспозиция на один сигнал не нужна.
     *
     * <p>«Торгуется» = у venue есть спеки контракта в config-service (V10, per-venue
     * {@code symbol_rules}). Отсутствие спеков означает, что монеты на этой бирже нет —
     * раньше такая нога уходила на биржу и падала ошибкой. PAPER/BACKTEST реестра контрактов не
     * имеют и эту проверку пропускают.
     *
     * <p>Ни одного подходящего аккаунта — пустой список: сигнал пропускается с явным логом и
     * метрикой {@code signal_venue_unavailable_total}, а не молча.
     */
    private List<AccountTarget> resolveTargets(TradingSignalPayload signal) {
        String symbol = signal.symbol();
        String action = signal.action();
        if (accountAdapters.isEmpty()) {
            return List.of(new AccountTarget(accountContext.currentAccountId(),
                    primaryAdapter.adapterName(), null, primaryAdapter));
        }
        List<AccountTarget> candidates = new ArrayList<>();
        accountAdapters.all().forEach((id, adapter) -> {
            BigDecimal notional = accountRegistry.byId(id).map(AccountProps::getNotionalPerTrade).orElse(null);
            candidates.add(new AccountTarget(id, adapter.adapterName(), notional, adapter));
        });

        // Выход обязан дойти до КАЖДОГО аккаунта, где позиция может стоять: приоритет venue тут
        // не при чём, а не закрытая из-за маршрутизации позиция — прямой убыток. Закрытие
        // важнее открытия — тот же принцип, что в risk-правилах.
        if ("CLOSE".equals(action) || "REDUCE".equals(action)) {
            return candidates;
        }
        // FLIP несёт в себе закрытие: он должен идти туда, где позиция УЖЕ есть. Если её нет ни
        // у кого, FLIP вырождается в OPEN и выбирает venue на общих основаниях (ниже).
        if ("FLIP".equals(action)) {
            List<AccountTarget> holders = candidates.stream()
                    .filter(t -> hasOpenPosition(t.accountId(), symbol))
                    .toList();
            if (!holders.isEmpty()) {
                return holders;
            }
        }
        candidates.sort(java.util.Comparator.comparingInt(t -> venueRank(t.exchange())));

        for (AccountTarget target : candidates) {
            if (!symbolTradableOn(target.exchange(), symbol)) {
                log.info("Venue {} пропущена для {}: нет спеков контракта (символ там не торгуется)",
                        target.exchange(), symbol);
                continue;
            }
            return List.of(target);
        }
        log.warn("Ни одна включённая биржа не торгует {} (кандидаты: {}) — сигнал пропущен",
                symbol, candidates.stream().map(AccountTarget::exchange).toList());
        if (meterRegistry != null) {
            meterRegistry.counter("signal_venue_unavailable_total", "symbol", symbol).increment();
        }
        return List.of();
    }

    private boolean hasOpenPosition(String accountId, String symbol) {
        return positionRepository.findByAccountIdAndSymbol(accountId, symbol)
                .map(p -> "OPEN".equals(p.getStatus()))
                .orElse(false);
    }

    /** Позиция в списке приоритета; venue вне списка идут последними. */
    private int venueRank(String exchange) {
        List<String> priority = props.getVenuePriority();
        int idx = priority == null ? -1 : priority.indexOf(exchange == null ? "" : exchange.toUpperCase());
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }

    private boolean symbolTradableOn(String exchange, String symbol) {
        if (exchange == null || VENUES_WITHOUT_CONTRACTS.contains(exchange.toUpperCase())) {
            return true;
        }
        if (configCache.hasSymbolRules(exchange, symbol)) {
            return true;
        }
        // Пустой реестр биржи = синк спеков ещё не отработал или config-service недоступен.
        // Трактовать это как «символ не торгуется» нельзя: один сбой конфигурации молча
        // остановил бы торговлю целиком. Пропускаем вперёд и даём бирже ответить самой.
        if (!configCache.hasVenueRegistry(exchange)) {
            log.warn("Реестр контрактов {} пуст — торгуемость {} не проверить, пропускаем вперёд",
                    exchange, symbol);
            return true;
        }
        // Реестр venue непуст, но именно этого символа в нём нет — не обязательно "не торгуется":
        // мог просто никогда не попасть в узкий config.symbols. On-demand спрашиваем биржу
        // напрямую (ADR-0032 Direction A) прежде чем сдаваться; live-смоук 18-19.08 (PAXGUSDT).
        if (onDemandSymbolRuleClient != null) {
            Optional<SymbolRules> rules = onDemandSymbolRuleClient.lookup(exchange, symbol);
            if (rules.isPresent()) {
                configCache.putSymbolRules(exchange, symbol, rules.get());
                log.info("On-demand спеки контракта {} {} получены — символ торгуется", exchange, symbol);
                return true;
            }
        }
        return false;
    }

    // NOT @Transactional — the two phases of DB work each have their own transaction
    // via TradingTransactionService (self-invocation prevention).
    //
    // Fan-out (P3): steps 1–4 (validity/gating/dedup/config) run ONCE per signal; the per-account
    // pipeline (steps 5–15) then runs for every enabled account, in parallel, with per-account error
    // isolation. Signal dedup stays global (one dedup per Kafka delivery, before fan-out).
    public void process(TradingSignalPayload signal, String correlationId) {
        String sym = signal.symbol();
        log.info("SignalProcessor: signalId={} symbol={} side={} action={} conf={}",
                signal.signalId(), sym, signal.side(), signal.action(), signal.confidence());

        // 1. Validity check
        if (System.currentTimeMillis() > signal.validUntil()) {
            log.warn("Signal expired: signalId={}", signal.signalId());
            return;
        }

        // 2. (removed) REDUCE is a first-class action since FU-4 — partial close, see step 6.

        // 3. Config check (safe default: no trading without config, ADR-0020). Done BEFORE the
        //    inbox claim so a config-not-ready skip leaves no PROCESSING row: the next delivery
        //    (once config loads) can still process it.
        if (!configCache.isConfigLoaded()) {
            log.warn("Config not loaded, skipping signal signalId={}", signal.signalId());
            return;
        }
        Optional<RiskProfile> profileOpt = configCache.getRiskProfile(sym);
        RiskProfile profile;
        if (profileOpt.isEmpty()) {
            log.warn("No risk profile for symbol={} — using global default (signalId={})", sym, signal.signalId());
            profile = configCache.getRiskProfileOrGlobalDefault(sym);
        } else {
            profile = profileOpt.get();
        }
        BigDecimal currentPrice = priceCache.getPrice(sym).orElse(null);

        // 4. Dedup/claim via signal_inbox (ADR-0014, M3) — own tiny tx. GLOBAL: once per signal,
        //    before fan-out. The row is PROCESSING; a crash before markInboxDone leaves it stale
        //    so a Kafka redelivery re-runs it instead of dropping the signal.
        if (!txService.dedupSignal(signal.idempotencyKey(), signal.signalId())) {
            return;
        }

        // Fan-out across accounts (1..N). Every path — including a single account — runs on the
        // fanoutExecutor under work.get(fanoutTimeoutMs), so a wedged exchange call can never pin
        // the trading.signals consumer thread. Previously the size==1 branch executed synchronously
        // ON the consumer thread with NO timeout (~25-30s worst-case block). Per-account work is
        // error-isolated (safeProcessForAccount). Single-account now inherits multi-account error
        // semantics: a business failure is logged + counted (signal_account_task_failed_total), NOT
        // DLQ-retried. Reconciliation converges positions against the exchange but does NOT
        // re-execute a missed entry — a transient failure on an OPEN loses that entry (accepted).
        List<AccountTarget> targets = resolveTargets(signal);
        log.info("Routing signalId={} action={}: {} target(s) {}", signal.signalId(), signal.action(),
                targets.size(), targets.stream().map(AccountTarget::exchange).toList());
        if (targets.isEmpty()) {
            txService.markInboxDone(signal.idempotencyKey());
            return;
        }
        List<CompletableFuture<Void>> futures = targets.stream()
                .map(t -> CompletableFuture.runAsync(
                        () -> safeProcessForAccount(signal, correlationId, profile, currentPrice, t),
                        fanoutExecutor))
                .toList();
        CompletableFuture<Void> work = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        try {
            work.get(fanoutTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            // Completed within the timeout: mark the inbox row DONE SYNCHRONOUSLY on this thread,
            // BEFORE the consumer returns and acks. A crash between the ack and this commit would
            // otherwise leave the row PROCESSING behind a committed offset, and a later redelivery of
            // the same key (>90s) would let the stale-reclaim reprocess an already-executed signal.
            txService.markInboxDone(signal.idempotencyKey());
        } catch (java.util.concurrent.TimeoutException e) {
            // Overrun: a wedged exchange call must not pin the trading.signals partition. The tasks
            // keep running; attach a best-effort late DONE (off-thread) so an eventual success is not
            // left PROCESSING forever. Per-account work is error-isolated (safeProcessForAccount) and
            // the message is acked (NOT redelivered) — reconciliation is the backstop; a failed/wedged
            // fan-out stays PROCESSING for a genuine redelivery + the 90s stale-reclaim to retry.
            work.whenComplete((v, ex) -> {
                if (ex != null) {
                    return;
                }
                try {
                    txService.markInboxDone(signal.idempotencyKey());
                } catch (RuntimeException me) {
                    log.warn("late markInboxDone failed for signalId={}: {}", signal.signalId(), me.getMessage());
                }
            });
            log.error("Fan-out for signalId={} did not finish within {}ms", signal.signalId(), fanoutTimeoutMs);
        } catch (java.util.concurrent.ExecutionException e) {
            // A task escaped safeProcessForAccount's isolation (should not happen); do not mark DONE.
            log.error("Fan-out for signalId={} failed: {}", signal.signalId(), String.valueOf(e.getCause()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Fan-out for signalId={} interrupted", signal.signalId());
        }
    }

    /**
     * Per-account fan-out worker with error isolation — one account's failure must not affect
     * others. A swallowed failure is NOT retried and NOT DLQ'd: reconciliation converges positions
     * against the exchange but cannot re-execute a missed entry, so a transient failure on an OPEN
     * (exchange 5xx, exhausted optimistic-lock retry) loses that entry. This is observable via
     * {@code signal_account_task_failed_total} — wire an alert on it before trusting it on live.
     */
    private void safeProcessForAccount(TradingSignalPayload signal, String correlationId,
                                       RiskProfile profile, BigDecimal currentPrice, AccountTarget target) {
        try {
            processForAccount(signal, correlationId, profile, currentPrice, target);
        } catch (RuntimeException e) {
            log.error("Fan-out account '{}' failed for signalId={}: {}",
                    target.accountId(), signal.signalId(), e.getMessage(), e);
            if (meterRegistry != null) {
                meterRegistry.counter("signal_account_task_failed_total",
                        "account", target.accountId(), "action", signal.action())
                        .increment();
            }
        }
    }

    /** The per-account pipeline (steps 5–15): position → risk → size → execute → state. */
    private void processForAccount(TradingSignalPayload signal, String correlationId,
                                   RiskProfile baseProfile, BigDecimal currentPrice, AccountTarget target) {
        String sym = signal.symbol();
        String accountId = target.accountId();
        String exchange = target.exchange();
        ExecutionAdapter adapter = target.adapter();
        RiskProfile profile = effectiveProfile(baseProfile, target);

        // 5. Position / open-count / cooldown for THIS account.
        Optional<PositionEntity> posOpt = positionRepository.findByAccountIdAndSymbol(accountId, sym);
        long openCount = positionRepository.countOpenByAccountId(accountId);
        Long lastLossAt = cooldownRepository.findByAccountIdAndSymbol(accountId, sym).map(e -> e.getLastLossAt()).orElse(null);

        // CLOSE/REDUCE with nothing open is a no-op, not an order.
        boolean exitAction = "CLOSE".equals(signal.action()) || "REDUCE".equals(signal.action());

        // Битые per-strategy TP/SL-параметры отсекаем ДО Tx1 и до роутинга FLIP: внутри executeLeg
        // команда PENDING_SUBMIT уже закоммичена, и throw оставлял бы её висеть навсегда без
        // REJECTED-результата, а на FLIP-пути CLOSE-нога к тому моменту уже исполнена — позиция
        // осталась бы закрытой без переоткрытия (ревью #88). Выходы не трогаем: битый параметр
        // ВХОДА не должен мешать закрыть позицию.
        if (!exitAction) {
            String paramError = executionEngine.validateStrategyTpSlParams(signal);
            if (paramError != null) {
                log.error("{} rejected for {} account={}: {}", signal.action(), sym, accountId, paramError);
                if (meterRegistry != null) {
                    meterRegistry.counter("strategy_param_invalid_total",
                            "strategy", String.valueOf(signal.strategyId())).increment();
                }
                publishRejectedResult(signal, accountId, exchange, correlationId, paramError);
                return;
            }
        }

        if (exitAction) {
            boolean flat = posOpt.isEmpty()
                    || "FLAT".equals(posOpt.get().getSide())
                    || "CLOSED".equals(posOpt.get().getStatus());
            if (flat) {
                log.warn("{} with no open position for {} account={}; skipping signalId={}",
                        signal.action(), sym, accountId, signal.signalId());
                publishRejectedResult(signal, accountId, exchange, correlationId, "NO_POSITION");
                return;
            }
        }

        // 6. Compute quantity (per-account notional via effective profile).
        //    REDUCE (FU-4): fraction of the open position; escalates to the FULL quantity when
        //    the position cannot be split without leaving unmanageable dust.
        BigDecimal rawQty;
        if ("CLOSE".equals(signal.action())) {
            rawQty = posOpt.get().getQuantity();
        } else if ("REDUCE".equals(signal.action())) {
            rawQty = resolveReduceQty(signal, posOpt.get(), currentPrice, accountId);
        } else {
            rawQty = positionSizer.computeQuantity(signal, profile, currentPrice);
        }

        // FLIP route (T18, ADR-0019)
        if ("FLIP".equals(signal.action())) {
            processFlip(signal, profile, posOpt, rawQty, currentPrice, openCount, lastLossAt,
                    correlationId, accountId, exchange, adapter);
            return;
        }

        // Переворот по обратному сигналу (volspike-pressure-v1, параметр flipEnabled, дефолт off):
        // OPEN противоположной стороной в живую позицию при включённом параметре → тот же
        // close-then-open путь, что и явный FLIP (ADR-0019). При выключенном — прежнее поведение
        // (риск-правила отвергнут вход в занятый символ).
        if ("OPEN".equals(signal.action()) && posOpt.isPresent()
                && "OPEN".equals(posOpt.get().getStatus())
                && !"FLAT".equals(posOpt.get().getSide())
                && !posOpt.get().getSide().equals(signal.side())
                && isFlipEnabled(signal.strategyId())) {
            // CLOSE-нога FLIP'а идёт мимо riskEngine (ADR-0019 Case 3) — поэтому базовые гейты
            // проверяем ЗДЕСЬ: kill switch/auto-pause/выключенный символ не должны обходиться
            // авто-переворотом (ревью #88, ADR-0018).
            if ("HALTED".equals(configCache.getGlobalTradingMode())
                    || riskEngine.isAutoPauseActive(accountId)
                    || configCache.isSymbolDisabled(sym)) {
                log.warn("flipEnabled: OPEN {} into live {} for {} account={} BLOCKED "
                        + "(halt/auto-pause/symbol-disabled) — no flip", signal.side(),
                        posOpt.get().getSide(), sym, accountId);
                publishRejectedResult(signal, accountId, exchange, correlationId, "FLIP_BLOCKED");
                return;
            }
            log.info("flipEnabled: OPEN {} into live {} position for {} account={} — routing as FLIP "
                    + "(close-then-open, ADR-0019)", signal.side(), posOpt.get().getSide(), sym, accountId);
            processFlip(signal, profile, posOpt, rawQty, currentPrice, openCount, lastLossAt,
                    correlationId, accountId, exchange, adapter);
            return;
        }

        // 7. Risk check (per-account health snapshot + PnL/drawdown).
        RiskContext ctx = new RiskContext(
                signal, profile,
                posOpt.orElse(null),
                openCount,
                currentPrice,
                pnlTracker.getTodayRealizedPnl(accountId),
                pnlTracker.getTodayMaxDrawdown(accountId),
                healthService.snapshot(sym, accountId),
                riskEngine.isAutoPauseActive(accountId),
                lastLossAt,
                exchange,
                accountId
        );
        RiskDecision decision = riskEngine.evaluate(ctx);

        if (decision.isBlocking()) {
            log.warn("Signal rejected by risk: signalId={} account={} reason={}",
                    signal.signalId(), accountId, decision.reason());
            publishRejectedResult(signal, accountId, exchange, correlationId, decision.reason());
            return;
        }

        if (decision.outcome() == RiskDecision.Outcome.REDUCE_SIZE && decision.sizeMultiplier() != null) {
            rawQty = rawQty.multiply(decision.sizeMultiplier()).setScale(10, RoundingMode.DOWN);
        }

        // 8. Normalize quantity
        BigDecimal finalQty;
        try {
            finalQty = exitAction
                    ? quantityNormalizer.normalizeClose(exchange, sym, rawQty)
                    : quantityNormalizer.normalize(exchange, sym, rawQty, currentPrice);
        } catch (Exception e) {
            log.warn("Quantity normalization failed: signalId={} account={} reason={}",
                    signal.signalId(), accountId, e.getMessage());
            publishRejectedResult(signal, accountId, exchange, correlationId, e.getMessage());
            return;
        }

        // 9-14. Execute via unified leg path (FU-9). Normal CLOSE sets cooldown on loss; REDUCE
        // does not — a partial trim is proactive risk reduction, not a stop-out (same as FLIP legs).
        PositionEntity position = posOpt.orElseGet(() -> PositionEntity.newFlat(accountId, exchange, sym));
        executeLeg(signal, finalQty, position, signal.signalId(), newCommandId(), correlationId,
                !"REDUCE".equals(signal.action()),
                accountId, exchange, adapter);

        // 15. Post-order reconciliation trigger (T17).
        eventPublisher.publishEvent(new ReconciliationTrigger(sym));
    }

    /**
     * Operator close-all for ONE account (admin panel / AccountOpsController). Synthesizes a
     * CLOSE leg per open position and runs it through the normal per-account pipeline — state
     * machine, order.results, trading.state, PnL — instead of the legacy one-shot exchange call
     * that bypasses local state (telegram /closeall). CLOSE is never risk-gated, so a halted or
     * auto-paused account can still be flattened.
     *
     * @param symbolFilter optional: close only this symbol; null/blank = all open positions.
     * @return per-symbol outcome: "CLOSED" or the failure reason.
     */
    public Map<String, String> closeAllForAccount(String accountId, String symbolFilter) {
        AccountTarget target = targetFor(accountId);
        if (target == null) {
            throw new IllegalArgumentException("unknown account: " + accountId);
        }
        List<PositionEntity> open = positionRepository.findAllOpenByAccountId(accountId).stream()
                .filter(p -> symbolFilter == null || symbolFilter.isBlank()
                        || p.getSymbol().equalsIgnoreCase(symbolFilter))
                .toList();
        Map<String, String> results = new LinkedHashMap<>();
        for (PositionEntity pos : open) {
            String sym = pos.getSymbol();
            RiskProfile profile = configCache.getRiskProfile(sym).orElse(null);
            if (profile == null) {
                // CLOSE needs no sizing config, only clamp rules — build a neutral profile.
                profile = new RiskProfile(sym, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        0, 0L, 0.0, configCache.getGlobalTradingMode());
            }
            String signalId = "manual-close-" + UUID.randomUUID();
            TradingSignalPayload closeSignal = new TradingSignalPayload(
                    signalId, "manual-ops", sym, pos.getSide(), "CLOSE",
                    1.0, "operator close-all", "manual",
                    System.currentTimeMillis(), System.currentTimeMillis() + 60_000,
                    null, signalId);
            try {
                processForAccount(closeSignal, signalId,
                        profile, priceCache.getPrice(sym).orElse(null), target);
                results.put(sym, "CLOSED");
            } catch (RuntimeException e) {
                log.error("close-all: account={} symbol={} failed: {}", accountId, sym, e.getMessage(), e);
                results.put(sym, "FAILED: " + e.getMessage());
            }
        }
        log.warn("[OPS] close-all account={} filter={} → {}", accountId, symbolFilter, results);
        return results;
    }

    /** Fan-out target for one account id; null when the id is unknown. */
    private AccountTarget targetFor(String accountId) {
        if (accountAdapters.isEmpty()) {
            return accountContext.currentAccountId().equals(accountId)
                    ? new AccountTarget(accountId, primaryAdapter.adapterName(), null, primaryAdapter)
                    : null;
        }
        return accountAdapters.get(accountId)
                .map(a -> new AccountTarget(accountId, a.adapterName(),
                        accountRegistry.byId(accountId).map(AccountProps::getNotionalPerTrade).orElse(null), a))
                .orElse(null);
    }

    /**
     * FU-4 REDUCE sizing: target = position × {@code trading.reduce.fraction}, floored to
     * stepSize. Escalates to the FULL position quantity when the position cannot be split:
     * the fraction floors to zero, or the remainder would fall below minQty/minNotional —
     * dust the exchange would refuse to close later (a stuck position the time-stop would
     * then fail to force-close in a reject loop).
     */
    /** Параметр стратегии flipEnabled из config-service (hot-reload через ConfigCache). */
    private boolean isFlipEnabled(String strategyId) {
        if (strategyId == null) {
            return false;
        }
        java.util.Optional<String> value = configCache.getStrategyParam(strategyId, "flipEnabled");
        return value != null && value.map("true"::equalsIgnoreCase).orElse(false);
    }

    private BigDecimal resolveReduceQty(TradingSignalPayload signal, PositionEntity position,
                                        BigDecimal currentPrice, String accountId) {
        String sym = signal.symbol();
        BigDecimal posQty = position.getQuantity();
        BigDecimal fraction = props.getReduce().getFraction();
        BigDecimal reduceQty;
        try {
            reduceQty = quantityNormalizer.normalizeClose(position.getExchange(), sym,
                    posQty.multiply(fraction));
        } catch (RuntimeException e) {
            return escalateReduce(signal, posQty, fraction, accountId, "fraction floors to zero");
        }
        // Спеки той биржи, где позиция реально стоит: dust считается по её минималкам (V10).
        SymbolRules rules = configCache.getSymbolRules(position.getExchange(), sym);
        BigDecimal remainder = posQty.subtract(reduceQty);
        boolean dust = remainder.signum() <= 0
                || (rules.minQty() != null && remainder.compareTo(rules.minQty()) < 0)
                || (rules.minNotional() != null && currentPrice != null && currentPrice.signum() > 0
                    && remainder.multiply(currentPrice).compareTo(rules.minNotional()) < 0);
        if (dust) {
            return escalateReduce(signal, posQty, fraction, accountId,
                    "remainder " + remainder + " would be dust (minQty/minNotional)");
        }
        return reduceQty;
    }

    private BigDecimal escalateReduce(TradingSignalPayload signal, BigDecimal posQty,
                                      BigDecimal fraction, String accountId, String why) {
        log.warn("REDUCE escalated to full close: signalId={} symbol={} account={} posQty={} fraction={} — {}",
                signal.signalId(), signal.symbol(), accountId, posQty, fraction, why);
        if (meterRegistry != null) {
            meterRegistry.counter("reduce_escalated_total", "symbol", signal.symbol()).increment();
        }
        return posQty;
    }

    /**
     * Overlay account-level config onto the symbol risk profile.
     * Notional priority: per-symbol account override → AccountProps.notionalPerTrade (ADR-0009) →
     * symbol profile. Risk fields: account override (non-null) → symbol profile. Mode: HALTED wins
     * from either side (global kill switch OR per-account halt) so TradingModeRule needs no change.
     */
    private RiskProfile effectiveProfile(RiskProfile profile, AccountTarget target) {
        AccountOverride override = configCache.getAccountOverride(target.accountId()).orElse(null);
        BigDecimal notional = firstNonNull(
                override != null ? override.notionalFor(profile.symbol()) : null,
                target.notionalOverride(),
                profile.notionalPerTrade());
        if (override == null) {
            return notional == profile.notionalPerTrade() ? profile
                    : new RiskProfile(profile.symbol(), notional, profile.maxDailyLoss(),
                            profile.maxDrawdown(), profile.maxOpenPositions(),
                            profile.cooldownAfterLossMs(), profile.minConfidence(), profile.tradingMode());
        }
        String mode = override.isHalted() ? "HALTED" : profile.tradingMode();
        return new RiskProfile(
                profile.symbol(),
                notional,
                firstNonNull(override.maxDailyLoss(), profile.maxDailyLoss()),
                firstNonNull(override.maxDrawdown(), profile.maxDrawdown()),
                override.maxOpenPositions() != null ? override.maxOpenPositions() : profile.maxOpenPositions(),
                override.cooldownAfterLossMs() != null ? override.cooldownAfterLossMs() : profile.cooldownAfterLossMs(),
                override.minConfidence() != null ? override.minConfidence() : profile.minConfidence(),
                mode);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        return null;
    }

    /**
     * ADR-0019 FLIP: close existing position (no risk gate — CLOSE_REDUCE always allowed),
     * then open in target side (risk-gated with refreshed context after close).
     */
    private void processFlip(TradingSignalPayload signal, RiskProfile profile,
                              Optional<PositionEntity> posOpt, BigDecimal rawOpenQty,
                              BigDecimal currentPrice, long openCount, Long lastLossAt,
                              String correlationId, String accountId, String exchange,
                              ExecutionAdapter adapter) {
        String sym = signal.symbol();
        PositionEntity position = posOpt.orElseGet(() -> PositionEntity.newFlat(accountId, exchange, sym));

        // Case 1: No existing position → degrade to regular risk-gated OPEN
        if ("FLAT".equals(position.getSide()) || "CLOSED".equals(position.getStatus())) {
            log.info("FLIP signalId={}: no position for {}; degrading to OPEN", signal.signalId(), sym);
            TradingSignalPayload openLeg = asLeg(signal, signal.side(), "OPEN");
            RiskContext ctx = new RiskContext(
                    openLeg, profile, position, openCount, currentPrice,
                    pnlTracker.getTodayRealizedPnl(accountId), pnlTracker.getTodayMaxDrawdown(accountId),
                    healthService.snapshot(sym, accountId), riskEngine.isAutoPauseActive(accountId), lastLossAt,
                    exchange, accountId);
            RiskDecision decision = riskEngine.evaluate(ctx);
            if (decision.isBlocking()) {
                log.warn("FLIP (degrade OPEN) risk rejected: signalId={} reason={}", signal.signalId(), decision.reason());
                publishRejectedResult(signal, accountId, exchange, correlationId, decision.reason());
                eventPublisher.publishEvent(new ReconciliationTrigger(sym));
                return;
            }
            BigDecimal qty = applyRiskMultiplier(rawOpenQty, decision);
            try {
                qty = quantityNormalizer.normalize(exchange, sym, qty, currentPrice);
            } catch (Exception e) {
                log.warn("FLIP (degrade OPEN) normalize failed: signalId={} reason={}", signal.signalId(), e.getMessage());
                publishRejectedResult(signal, accountId, exchange, correlationId, e.getMessage());
                eventPublisher.publishEvent(new ReconciliationTrigger(sym));
                return;
            }
            executeLeg(openLeg, qty, position, signal.signalId(), newCommandId(), correlationId, false,
                    accountId, exchange, adapter);
            eventPublisher.publishEvent(new ReconciliationTrigger(sym));
            return;
        }

        // Case 2: Already in target side → no-op
        if (signal.side().equals(position.getSide())) {
            log.info("FLIP signalId={}: already in target side {}; skipping (no-op)", signal.signalId(), signal.side());
            return;
        }

        // Case 3: True flip — CLOSE then OPEN
        TradingSignalPayload closeLeg = asLeg(signal, position.getSide(), "CLOSE");
        BigDecimal closeQty = position.getQuantity();
        boolean closeFilled = executeLeg(closeLeg, closeQty, position, signal.signalId(), newCommandId(), correlationId, false,
                accountId, exchange, adapter);

        if (!closeFilled) {
            log.warn("FLIP signalId={}: CLOSE-leg not fully filled for {}; aborting OPEN-leg", signal.signalId(), sym);
            publishRejectedResult(signal, accountId, exchange, correlationId, "FLIP aborted: CLOSE-leg not fully filled");
            eventPublisher.publishEvent(new ReconciliationTrigger(sym));
            return;
        }

        // Re-evaluate risk for OPEN-leg — flush so countOpen() reflects the closed position.
        positionRepository.flush();
        long refreshedOpenCount = positionRepository.countOpenByAccountId(accountId);
        BigDecimal refreshedPrice = priceCache.getPrice(sym).orElse(currentPrice);
        Long refreshedLastLossAt = cooldownRepository.findByAccountIdAndSymbol(accountId, sym).map(e -> e.getLastLossAt()).orElse(null);

        TradingSignalPayload openLeg = asLeg(signal, signal.side(), "OPEN");
        RiskContext openCtx = new RiskContext(
                openLeg, profile, position, refreshedOpenCount, refreshedPrice,
                pnlTracker.getTodayRealizedPnl(accountId), pnlTracker.getTodayMaxDrawdown(accountId),
                healthService.snapshot(sym, accountId), riskEngine.isAutoPauseActive(accountId), refreshedLastLossAt,
                exchange, accountId);
        RiskDecision openDecision = riskEngine.evaluate(openCtx);

        if (openDecision.isBlocking()) {
            log.warn("FLIP signalId={}: CLOSE ok, OPEN risk rejected for {}; staying FLAT. reason={}",
                    signal.signalId(), sym, openDecision.reason());
            publishRejectedResult(signal, accountId, exchange, correlationId,
                    "FLIP-OPEN risk rejected: " + openDecision.reason());
            eventPublisher.publishEvent(new ReconciliationTrigger(sym));
            return;
        }

        BigDecimal openQty = applyRiskMultiplier(rawOpenQty, openDecision);
        try {
            openQty = quantityNormalizer.normalize(exchange, sym, openQty, refreshedPrice);
        } catch (Exception e) {
            log.warn("FLIP signalId={}: OPEN-leg normalize failed for {}; staying FLAT: {}",
                    signal.signalId(), sym, e.getMessage());
            publishRejectedResult(signal, accountId, exchange, correlationId, "FLIP-OPEN normalize failed: " + e.getMessage());
            eventPublisher.publishEvent(new ReconciliationTrigger(sym));
            return;
        }

        executeLeg(openLeg, openQty, position, signal.signalId(), newCommandId(), correlationId, false,
                accountId, exchange, adapter);
        eventPublisher.publishEvent(new ReconciliationTrigger(sym));
    }

    /**
     * Unified execution path (FU-9): Tx1 → HTTP → Tx2.
     * Returns true only when the leg is FULLY filled.
     *
     * @param setCooldownOnLoss true for normal CLOSE (stop-out → cooldown), false for FLIP legs (BUG-5)
     * @param exchange          venue label for the account (adapter name)
     * @param adapter           the account's execution adapter (fan-out routing, P3)
     */
    private boolean executeLeg(TradingSignalPayload legSignal, BigDecimal qty,
                                PositionEntity position, String parentSignalId,
                                String commandId, String correlationId,
                                boolean setCooldownOnLoss, String accountId,
                                String exchange, ExecutionAdapter adapter) {
        // client_order_id = commandId (P2): ≤36 chars on every venue (Binance limit), globally
        // unique via the UUID commandId. The account dimension lives in its own column, not the id.
        String clientOrderId = commandId;

        // Tx1: save PENDING_SUBMIT command + outbox(order.commands)
        OrderCommandEntity cmd = txService.savePendingSubmitCommand(
                commandId, accountId, parentSignalId, clientOrderId, exchange, legSignal, qty, correlationId);

        long startMs = System.currentTimeMillis();

        // Top-of-book snapshot of THIS leg's venue BEFORE the send — the reference the fill is
        // judged against (same per-venue rule as the slippage gate).
        com.sonar.trading.exchange.marketdata.BookTickerQuote slipRef =
                slippageRecorder != null ? slippageRecorder.snapshot(exchange, legSignal.symbol()) : null;

        // Exchange HTTP call — outside any transaction, routed to THIS account's adapter.
        AdapterOrderResult result = executionEngine.execute(legSignal, qty, clientOrderId, adapter);

        if (slippageRecorder != null) {
            slippageRecorder.record(slipRef, exchange, legSignal.symbol(),
                    legSignal.action(), legSignal.side(), result);
        }

        if (meterRegistry != null && result.filledQty() != null && result.filledQty().signum() > 0) {
            meterRegistry.timer("order_fill_latency_ms", "symbol", legSignal.symbol())
                    .record(System.currentTimeMillis() - startMs, TimeUnit.MILLISECONDS);
        }

        // Tx2: order + cmd status + position + PnL + outbox(results)
        BigDecimal entryPriceBeforeClose = position.getEntryPrice();
        try {
            return txService.executeTx2(
                    legSignal, cmd, position, result,
                    correlationId, parentSignalId, setCooldownOnLoss, entryPriceBeforeClose);
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            // The reconciler wrote the position row between our read (before the HTTP call) and
            // this commit. Its copy is fresher exchange truth; re-applying the fill on a reload
            // could double-count it. Persist everything except the position row and hand the row
            // to reconciliation (exchange=truth, ADR-0027).
            log.warn("Tx2 optimistic-lock conflict on position {} (accountId={}) — persisting order/PnL "
                    + "without the position write; reconciliation will converge the row",
                    legSignal.symbol(), accountId);
            if (meterRegistry != null) {
                meterRegistry.counter("position_version_conflict_total", "symbol", legSignal.symbol())
                        .increment();
            }
            boolean fullyFilled = txService.executeTx2WithoutPositionWrite(
                    legSignal, cmd, position, result,
                    correlationId, parentSignalId, setCooldownOnLoss, entryPriceBeforeClose);
            eventPublisher.publishEvent(new ReconciliationTrigger(legSignal.symbol()));
            return fullyFilled;
        }
    }

    private TradingSignalPayload asLeg(TradingSignalPayload sig, String side, String action) {
        return new TradingSignalPayload(
                sig.signalId(), sig.strategyId(), sig.symbol(),
                side, action,
                sig.confidence(), sig.reason(), sig.sourceWindow(),
                sig.generatedAt(), sig.validUntil(),
                sig.sourceMarketEventId(), sig.idempotencyKey(),
                sig.entryPrice(), sig.slPrice(), sig.tpPrice(), sig.qty(), sig.maxHoldMs());
    }

    private BigDecimal applyRiskMultiplier(BigDecimal qty, RiskDecision decision) {
        if (decision.outcome() == RiskDecision.Outcome.REDUCE_SIZE && decision.sizeMultiplier() != null) {
            return qty.multiply(decision.sizeMultiplier()).setScale(10, RoundingMode.DOWN);
        }
        return qty;
    }

    private String newCommandId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void publishRejectedResult(TradingSignalPayload signal, String accountId, String exchange,
                                       String correlationId, String reason) {
        orderResultProducer.publish(
                "N/A", signal.signalId(), "N/A",
                null, accountId, exchange, signal.symbol(),
                "REJECTED",
                null, null, null,
                "RISK_REJECTED", reason,
                correlationId, signal.signalId()
        );
    }
}
