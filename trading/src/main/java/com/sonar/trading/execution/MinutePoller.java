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
import com.sonar.trading.exchange.bingx.BingxFuturesClient;
import com.sonar.trading.exchange.bingx.BingxOrderMapper;
import com.sonar.trading.reconciliation.BingxAccountParser;
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
 * 4.7: the ONE periodic poll in the system. Per tradable BingX account: pulls balance +
 * positions, detects closes, verifies stops, flags DB/exchange qty drift, reminds on long holds,
 * and maintains {@code daily_baseline} (4.2 — not a separate scheduler, this tick owns it).
 */
@Component
public class MinutePoller {

    private static final Logger log = LoggerFactory.getLogger(MinutePoller.class);
    private static final BigDecimal ANOMALY_DROP_THRESHOLD = new BigDecimal("0.5"); // grabля #2 (10)
    private static final BigDecimal QTY_EPSILON = new BigDecimal("0.0001");

    private final AccountRegistry accountRegistry;
    private final AccountRiskRepository accountRiskRepository;
    private final PositionRepository positionRepository;
    private final StrategySettingsRepository strategySettingsRepository;
    private final NotificationRepository notificationRepository;
    private final BingxFuturesClient bingxFuturesClient;
    private final StopGuard stopGuard;
    private final PollerTransactions transactions;

    /** In-memory only — a false negative right after a restart is an acceptable trade-off for a
     *  demo-reset guard (10, grabля #2); the next tick has a real baseline to compare against. */
    private final Map<String, BigDecimal> lastSeenEquity = new ConcurrentHashMap<>();
    private final Map<String, LocalDate> lastHoldReminder = new ConcurrentHashMap<>();

    public MinutePoller(AccountRegistry accountRegistry, AccountRiskRepository accountRiskRepository,
                        PositionRepository positionRepository,
                        StrategySettingsRepository strategySettingsRepository,
                        NotificationRepository notificationRepository, BingxFuturesClient bingxFuturesClient,
                        StopGuard stopGuard, PollerTransactions transactions) {
        this.accountRegistry = accountRegistry;
        this.accountRiskRepository = accountRiskRepository;
        this.positionRepository = positionRepository;
        this.strategySettingsRepository = strategySettingsRepository;
        this.notificationRepository = notificationRepository;
        this.bingxFuturesClient = bingxFuturesClient;
        this.stopGuard = stopGuard;
        this.transactions = transactions;
    }

    @Scheduled(fixedRate = 60_000)
    public void tick() {
        for (AccountProps account : accountRegistry.all()) {
            if (!"bingx".equalsIgnoreCase(account.exchange())) {
                continue; // Binance execution not wired yet (parked, stage 3 scope was BingX-only)
            }
            AccountRiskEntity risk = accountRiskRepository.findById(account.id()).orElse(null);
            if (risk == null || !risk.isTradable()) {
                continue;
            }
            try {
                pollAccount(account.id());
            } catch (RuntimeException e) {
                log.error("[poller] account {} tick failed: {}", account.id(), e.toString());
            }
        }
    }

    private void pollAccount(String accountId) {
        ExchangeBalance balance = fetchBalance();
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
            updateDailyBaseline(accountId, equity);
        }

        Map<String, Object> positionsResp = bingxFuturesClient.getPositions(null);
        List<ExchangePosition> exchangePositions = BingxAccountParser.parsePositions(positionsResp);

        for (PositionEntity dbPosition : positionRepository.findAllOpenByAccountId(accountId)) {
            reconcilePosition(accountId, dbPosition, exchangePositions);
        }
    }

    private ExchangeBalance fetchBalance() {
        try {
            Map<String, Object> resp = bingxFuturesClient.getBalance();
            return BingxAccountParser.parseBalance(resp, "VST").orElse(null);
        } catch (RuntimeException e) {
            log.error("[poller] getBalance failed: {}", e.toString());
            return null;
        }
    }

    private void updateDailyBaseline(String accountId, BigDecimal equity) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        BigDecimal realizedToday = sumIncomeSince(accountId, today.atStartOfDay(ZoneOffset.UTC).toInstant());
        transactions.upsertDailyBaseline(accountId, today, equity, realizedToday);
    }

    /** Реализованный PnL = закрытые сделки + комиссии + фандинг (4.2) — recomputed fresh each tick
     *  from the exchange's own income ledger rather than accumulated incrementally, so a missed
     *  tick or a double-run can never double-count. */
    @SuppressWarnings("unchecked")
    private BigDecimal sumIncomeSince(String accountId, Instant since) {
        try {
            Map<String, Object> resp = bingxFuturesClient.getIncome(null, since.toEpochMilli(),
                    System.currentTimeMillis(), 1000);
            Object data = resp.get("data");
            if (!(data instanceof List<?> rows)) {
                return null;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (Object o : rows) {
                if (o instanceof Map<?, ?> row) {
                    BigDecimal income = BingxOrderMapper.extractDecimal((Map<String, Object>) row, "income");
                    if (income != null) {
                        sum = sum.add(income);
                    }
                }
            }
            return sum;
        } catch (RuntimeException e) {
            log.warn("[poller] {} getIncome failed, keeping previous realized_pnl: {}", accountId, e.toString());
            return null;
        }
    }

    private void reconcilePosition(String accountId, PositionEntity dbPosition, List<ExchangePosition> exchangePositions) {
        Optional<ExchangePosition> match = exchangePositions.stream()
                .filter(p -> p.symbol().equalsIgnoreCase(dbPosition.getSymbol()) && !p.isFlat())
                .findFirst();

        if (match.isEmpty()) {
            handleClosed(accountId, dbPosition);
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

        boolean stopOk = stopGuard.verifyAndRepair(dbPosition.getSymbol(), live.qty(),
                dbPosition.getSlPrice() != null ? new BigDecimal(dbPosition.getSlPrice()) : null);
        if (!stopOk) {
            notify(NotificationEntity.MISSING_STOP, accountId, dbPosition.getSymbol(),
                    "позиция без стопа, автовосстановление не удалось — требуется ручное вмешательство");
        }

        checkHoldReminder(accountId, dbPosition);
    }

    private void handleClosed(String accountId, PositionEntity dbPosition) {
        BigDecimal pnl = dbPosition.getOpenedAt() != null
                ? sumIncomeSince(accountId, Instant.ofEpochMilli(dbPosition.getOpenedAt()))
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
