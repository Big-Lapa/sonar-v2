package com.sonar.trading.execution;

import com.sonar.trading.config.AccountProps;
import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.entity.AccountRiskEntity;
import com.sonar.trading.domain.entity.NotificationEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.entity.StrategySettingsEntity;
import com.sonar.trading.domain.repository.AccountRiskRepository;
import com.sonar.trading.domain.repository.NotificationRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.domain.repository.StrategySettingsRepository;
import com.sonar.trading.reconciliation.AccountReader;
import com.sonar.trading.reconciliation.AccountReaderRegistry;
import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangePosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 4.7: the ONE periodic poll in the system. Per <b>active</b> account (not "tradable" — an
 * account can be watched without Sonar being allowed to trade it): pulls balance + positions,
 * adopts positions opened directly on the exchange that have no DB row yet (the exchange is the
 * source of truth for "is this open", not our own execution history), detects closes, verifies
 * stops, flags DB/exchange qty drift, reminds on long holds, and maintains {@code daily_baseline}
 * (4.2 — not a separate scheduler, this tick owns it).
 *
 * <p>Venue-agnostic (grilled Q5/full multi-venue routing): resolves the account's
 * {@link AccountReader} via {@link AccountReaderRegistry} instead of a hardcoded BingX-only
 * filter — any account whose venue has no wired reader (venue not yet supported) is skipped with
 * a log line, same posture the old "bingx"-only check had for everything else.
 */
@Component
public class MinutePoller {

    private static final Logger log = LoggerFactory.getLogger(MinutePoller.class);
    private static final BigDecimal ANOMALY_DROP_THRESHOLD = new BigDecimal("0.5"); // grabля #2 (10)
    private static final BigDecimal QTY_EPSILON = new BigDecimal("0.0001");

    private final AccountRegistry accountRegistry;
    private final AccountReaderRegistry accountReaders;
    private final AccountRiskRepository accountRiskRepository;
    private final PositionRepository positionRepository;
    private final StrategySettingsRepository strategySettingsRepository;
    private final NotificationRepository notificationRepository;
    private final StopGuard stopGuard;
    private final PollerTransactions transactions;

    /** In-memory only — a false negative right after a restart is an acceptable trade-off for a
     *  demo-reset guard (10, grabля #2); the next tick has a real baseline to compare against. */
    private final Map<String, BigDecimal> lastSeenEquity = new ConcurrentHashMap<>();
    private final Map<String, LocalDate> lastHoldReminder = new ConcurrentHashMap<>();

    public MinutePoller(AccountRegistry accountRegistry, AccountReaderRegistry accountReaders,
                        AccountRiskRepository accountRiskRepository, PositionRepository positionRepository,
                        StrategySettingsRepository strategySettingsRepository,
                        NotificationRepository notificationRepository, StopGuard stopGuard,
                        PollerTransactions transactions) {
        this.accountRegistry = accountRegistry;
        this.accountReaders = accountReaders;
        this.accountRiskRepository = accountRiskRepository;
        this.positionRepository = positionRepository;
        this.strategySettingsRepository = strategySettingsRepository;
        this.notificationRepository = notificationRepository;
        this.stopGuard = stopGuard;
        this.transactions = transactions;
    }

    @Scheduled(fixedRate = 60_000)
    public void tick() {
        for (AccountProps account : accountRegistry.all()) {
            Optional<AccountReader> reader = accountReaders.forAccount(account.id());
            if (reader.isEmpty()) {
                continue; // venue not wired (e.g. Binance — parked in _stage3-pending) or PAPER mode
            }
            // active gates monitoring (balance/positions/stop-verify/close-notify); tradable is a
            // separate, narrower gate checked only where Sonar itself might open a position
            // (ProposalPreviewService) — an account can be watched without being tradable, which
            // is exactly the state a freshly-added read-only venue (e.g. Pionex) starts in.
            // A freshly registered account has no risk row yet — that must not mean "unmonitored".
            // The row was previously created lazily, only when someone opened the account's risk
            // profile in the panel, so a new account silently got no balance polling, no position
            // adoption and no close notifications until a human happened to click it. Create it
            // here with its defaults (active=true, tradable=false) instead.
            AccountRiskEntity risk = accountRiskRepository.findById(account.id())
                    .orElseGet(() -> accountRiskRepository.save(AccountRiskEntity.defaultsFor(account.id())));
            if (!risk.isActive()) {
                continue;
            }
            try {
                pollAccount(account.id(), account.exchange(), reader.get());
            } catch (RuntimeException e) {
                log.error("[poller] account {} tick failed: {}", account.id(), e.toString());
            }
        }
    }

    private void pollAccount(String accountId, String exchange, AccountReader reader) {
        ExchangeBalance balance = fetchBalance(accountId, reader);
        BigDecimal equity = balance == null ? null
                : balance.equity() != null ? balance.equity()
                : (balance.balance() != null && balance.unrealizedPnl() != null
                        ? balance.balance().add(balance.unrealizedPnl()) : balance.balance());

        boolean anomalyDrop = false;
        BigDecimal previous = lastSeenEquity.get(accountId);
        if (equity != null) {
            if (previous != null && previous.signum() > 0
                    && equity.compareTo(previous.multiply(ANOMALY_DROP_THRESHOLD)) < 0) {
                anomalyDrop = true;
                log.error("[poller] {} equity dropped >50% in one tick ({} -> {}) — suspected demo-balance "
                        + "reset, NOT touching daily_baseline or auto-pausing (10, грабля #2)", accountId, previous, equity);
                notify(NotificationEntity.ANOMALY_EQUITY, accountId, null,
                        "аномальное падение эквити " + previous + " -> " + equity
                                + " — похоже на сброс демо-баланса, требуется подтверждение оператора");
            }
            lastSeenEquity.put(accountId, equity);
        }

        if (equity != null && !anomalyDrop) {
            updateDailyBaseline(accountId, equity, reader);
        }

        List<ExchangePosition> exchangePositions = reader.getPositions();
        List<PositionEntity> dbOpenPositions = positionRepository.findAllOpenByAccountId(accountId);

        adoptUntracked(accountId, exchange, dbOpenPositions, exchangePositions);

        for (PositionEntity dbPosition : dbOpenPositions) {
            reconcilePosition(accountId, exchange, dbPosition, exchangePositions, reader);
        }
    }

    /**
     * The exchange is the source of truth for "is a position open", not our own execution
     * history — a position opened directly on the venue (manual panel bypassed, another tool, a
     * pre-existing account) gets exactly the same DB row a Sonar-opened one would, so it shows up
     * in the panel and gets the same close/stop-missing notifications going forward. SL/TP/planned
     * risk stay null — genuinely unknown, not fabricated; {@code opened_via='EXCHANGE'} is the only
     * marker distinguishing it, and nothing else reads that column (see its javadoc).
     *
     * <p>Runs before {@link #reconcilePosition}, so a symbol adopted this same tick is reconciled
     * immediately after (stop-verified, etc.) rather than waiting a full minute.
     */
    private void adoptUntracked(String accountId, String exchange, List<PositionEntity> dbOpenPositions,
                                List<ExchangePosition> exchangePositions) {
        for (ExchangePosition live : exchangePositions) {
            if (live.isFlat()) {
                continue;
            }
            boolean alreadyTracked = dbOpenPositions.stream()
                    .anyMatch(p -> p.getSymbol().equalsIgnoreCase(live.symbol()));
            if (alreadyTracked) {
                continue;
            }
            // Reuse the lifetime accountId+symbol row if one exists (e.g. a position Sonar opened
            // and closed previously) rather than violating positionId's unique constraint.
            PositionEntity position = positionRepository.findByAccountIdAndSymbol(accountId, live.symbol())
                    .orElseGet(() -> PositionEntity.newFlat(accountId, exchange.toUpperCase(java.util.Locale.ROOT), live.symbol()));
            position.setSide(live.side());
            position.setQuantity(live.qty());
            position.setEntryPrice(live.avgPrice());
            position.setMarkPrice(live.markPrice());
            position.setUnrealizedPnl(live.unrealizedPnl());
            position.setLeverage(live.leverage());
            position.setLiquidationPrice(live.liquidationPrice());
            position.setStatus("OPEN");
            position.setOpenedAt(System.currentTimeMillis());
            position.setOpenedVia("EXCHANGE");
            position.setUpdatedAt(System.currentTimeMillis());
            positionRepository.save(position);
            dbOpenPositions.add(position);

            log.info("[poller] {} adopted {} {} qty={} opened directly on the exchange (no DB row existed)",
                    accountId, live.symbol(), live.side(), live.qty());
            notify(NotificationEntity.POSITION_ADOPTED, accountId, live.symbol(),
                    live.side() + " " + live.qty() + " @ " + live.avgPrice()
                            + " — открыта на бирже напрямую, начал отслеживать (SL/TP неизвестны)");
        }
    }

    private ExchangeBalance fetchBalance(String accountId, AccountReader reader) {
        try {
            return reader.getBalance().orElse(null);
        } catch (RuntimeException e) {
            log.error("[poller] {} getBalance failed: {}", accountId, e.toString());
            return null;
        }
    }

    private void updateDailyBaseline(String accountId, BigDecimal equity, AccountReader reader) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        BigDecimal realizedToday = reader.sumIncomeSince(today.atStartOfDay(ZoneOffset.UTC).toInstant());
        transactions.upsertDailyBaseline(accountId, today, equity, realizedToday);
    }

    private void reconcilePosition(String accountId, String exchange, PositionEntity dbPosition,
                                   List<ExchangePosition> exchangePositions, AccountReader reader) {
        Optional<ExchangePosition> match = exchangePositions.stream()
                .filter(p -> p.symbol().equalsIgnoreCase(dbPosition.getSymbol()) && !p.isFlat())
                .findFirst();

        if (match.isEmpty()) {
            handleClosed(accountId, dbPosition, reader);
            return;
        }
        ExchangePosition live = match.get();

        BigDecimal drift = live.qty().subtract(dbPosition.getQuantity()).abs();
        boolean mismatch = drift.compareTo(QTY_EPSILON) > 0;
        if (mismatch != dbPosition.isQtyMismatch()) {
            transactions.saveQtyMismatch(dbPosition.getId(), mismatch);
            if (mismatch) {
                notify(NotificationEntity.QTY_MISMATCH, accountId, dbPosition.getSymbol(),
                        "расхождение количества: БД=" + dbPosition.getQuantity() + " биржа=" + live.qty());
            }
        }

        // Stop verification is a hard requirement on every venue that can answer it — a position
        // without a stop is exactly what MISSING_STOP exists to shout about, every tick, until an
        // operator fixes it. The one exception is a venue whose API physically cannot answer the
        // question: Pionex's Futures API (Beta) has no TP/SL surface at all — not in the order
        // schema, not as an endpoint, not over WebSocket (verified against their published OpenAPI
        // specs). There, TP/SL lives only inside the exchange's own web UI, so an alert here would
        // fire forever while telling the operator nothing they can act on through us. Log it and
        // stay quiet; the risk is theirs to manage on the exchange until Pionex ships the API.
        if (stopGuard.isSupported(accountId)) {
            // ONE read of the venue's resting orders feeds both the level sync and the stop check.
            // They were two calls on the same endpoint per position per tick, which is what earned
            // a frequency-limit ban from BingX.
            StopGuard.StopInspection stops = stopGuard.inspect(accountId, dbPosition.getSymbol());
            syncStopLevels(accountId, dbPosition, stops);
            BigDecimal slForRepair = stops.stopLoss() != null ? stops.stopLoss()
                    : dbPosition.getSlPrice() != null ? new BigDecimal(dbPosition.getSlPrice()) : null;
            boolean stopOk = stopGuard.verifyAndRepair(accountId, dbPosition.getSymbol(), live.qty(),
                    slForRepair, stops);
            if (!stopOk) {
                notify(NotificationEntity.MISSING_STOP, accountId, dbPosition.getSymbol(),
                        "позиция без стопа, автовосстановление не удалось — требуется ручное вмешательство");
            }
        } else {
            log.debug("[poller] {} {} stop check skipped — venue '{}' exposes no TP/SL API",
                    accountId, dbPosition.getSymbol(), exchange);
        }

        checkHoldReminder(accountId, dbPosition);
    }

    /**
     * Pulls SL/TP back from the venue onto the DB row. A position Sonar opened already carries the
     * levels it attached at entry, but one adopted from the exchange carries nothing — the panel
     * showed "— / —" for stops that were in fact sitting on the exchange the whole time. The venue
     * is the source of truth here too: if the operator moved a stop by hand, this picks that up on
     * the next tick rather than leaving our copy stale.
     */
    private void syncStopLevels(String accountId, PositionEntity dbPosition, StopGuard.StopInspection stops) {
        if (!stops.readable() || (stops.stopLoss() == null && stops.takeProfit() == null)) {
            return; // nothing learned — keep whatever is on record rather than blanking it
        }
        String sl = stops.stopLoss() != null ? stops.stopLoss().toPlainString() : null;
        String tp = stops.takeProfit() != null ? stops.takeProfit().toPlainString() : null;
        if (!java.util.Objects.equals(sl, dbPosition.getSlPrice())
                || !java.util.Objects.equals(tp, dbPosition.getTpPrice())) {
            transactions.saveStopLevels(dbPosition.getId(), sl, tp);
            log.info("[poller] {} {} stop levels from exchange: SL={} TP={}",
                    accountId, dbPosition.getSymbol(), sl, tp);
        }
    }

    private void handleClosed(String accountId, PositionEntity dbPosition, AccountReader reader) {
        BigDecimal pnl = dbPosition.getOpenedAt() != null
                ? reader.sumIncomeSince(Instant.ofEpochMilli(dbPosition.getOpenedAt()))
                : null;
        long heldMs = dbPosition.getOpenedAt() != null ? System.currentTimeMillis() - dbPosition.getOpenedAt() : 0;
        Duration held = Duration.ofMillis(heldMs);

        transactions.closePosition(dbPosition.getId());

        notify(NotificationEntity.POSITION_CLOSED, accountId, dbPosition.getSymbol(),
                dbPosition.getSymbol() + " закрыт, PnL=" + (pnl != null ? pnl : "уточняется")
                        + ", удержание=" + held.toHours() + "ч" + (held.toMinutesPart()) + "м");
        log.info("[poller] {} {} detected closed (pnl={}, held={})", accountId, dbPosition.getSymbol(), pnl, held);
    }

    private void checkHoldReminder(String accountId, PositionEntity dbPosition) {
        if (dbPosition.getOpenedAt() == null) {
            return;
        }
        StrategySettingsEntity settings = strategySettingsRepository.findById(StrategySettingsEntity.SINGLETON_ID)
                .orElseGet(() -> strategySettingsRepository.save(StrategySettingsEntity.defaults()));
        long heldDays = Duration.ofMillis(System.currentTimeMillis() - dbPosition.getOpenedAt()).toDays();
        if (heldDays < settings.getHoldReminderDays()) {
            return;
        }
        String key = accountId + ":" + dbPosition.getSymbol();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (today.equals(lastHoldReminder.get(key))) {
            return; // one reminder per calendar day is enough
        }
        lastHoldReminder.put(key, today);
        notify(NotificationEntity.HOLD_REMINDER, accountId, dbPosition.getSymbol(),
                dbPosition.getSymbol() + " держится " + heldDays + " дн. (порог " + settings.getHoldReminderDays() + ")");
    }

    private void notify(String kind, String accountId, String symbol, String message) {
        notificationRepository.save(new NotificationEntity(kind, accountId, symbol, message));
    }
}
