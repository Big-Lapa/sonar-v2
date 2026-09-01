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
 * 4.7: the ONE periodic poll in the system. Per tradable account: pulls balance + positions,
 * detects closes, verifies stops, flags DB/exchange qty drift, reminds on long holds, and
 * maintains {@code daily_baseline} (4.2 — not a separate scheduler, this tick owns it).
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
            Optional<AccountReader> reader = accountReaders.forExchange(account.exchange());
            if (reader.isEmpty()) {
                continue; // venue not wired (e.g. Binance — parked in _stage3-pending) or PAPER mode
            }
            AccountRiskEntity risk = accountRiskRepository.findById(account.id()).orElse(null);
            if (risk == null || !risk.isTradable()) {
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

        for (PositionEntity dbPosition : positionRepository.findAllOpenByAccountId(accountId)) {
            reconcilePosition(accountId, exchange, dbPosition, exchangePositions, reader);
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

        boolean stopOk = stopGuard.verifyAndRepair(exchange, dbPosition.getSymbol(), live.qty(),
                dbPosition.getSlPrice() != null ? new BigDecimal(dbPosition.getSlPrice()) : null);
        if (!stopOk) {
            notify(NotificationEntity.MISSING_STOP, accountId, dbPosition.getSymbol(),
                    "позиция без стопа, автовосстановление не удалось — требуется ручное вмешательство");
        }

        checkHoldReminder(accountId, dbPosition);
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
