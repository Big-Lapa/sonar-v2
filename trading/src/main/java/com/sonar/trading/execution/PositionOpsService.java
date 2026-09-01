package com.sonar.trading.execution;

import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.entity.NotificationEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.repository.NotificationRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code /closeall}, {@code /be}, {@code /move} (4.7/5) — manual operator actions on an
 * already-open position.
 *
 * <p>Venue-agnostic (grilled Q5): resolves the right {@link ExecutionAdapter} per account via
 * {@link ExecutionAdapterRegistry} instead of talking to a BingX-specific client — one class
 * serves every wired venue.
 */
@Service
public class PositionOpsService {

    private static final Logger log = LoggerFactory.getLogger(PositionOpsService.class);

    private final PositionRepository positionRepository;
    private final AccountRegistry accountRegistry;
    private final ExecutionAdapterRegistry adapters;
    private final NotificationRepository notificationRepository;

    public PositionOpsService(PositionRepository positionRepository, AccountRegistry accountRegistry,
                              ExecutionAdapterRegistry adapters, NotificationRepository notificationRepository) {
        this.positionRepository = positionRepository;
        this.accountRegistry = accountRegistry;
        this.adapters = adapters;
        this.notificationRepository = notificationRepository;
    }

    public sealed interface OpResult {
        record Ok(String message) implements OpResult {}
        record Failed(String reason) implements OpResult {}
    }

    /**
     * Reduce-only close of every open position for the account (optionally scoped to one symbol)
     * via the venue's native close-all (BingX: bulk endpoint; Bybit: synthesized + verified —
     * grilled Q3). DB status is NOT flipped here — the next minute poller tick (≤60s) detects the
     * close on the exchange and records it with PnL, same single source of truth as an SL/TP-triggered close.
     */
    public OpResult closeAll(String accountId, String symbol) {
        List<PositionEntity> targets = symbol != null
                ? positionRepository.findByAccountIdAndSymbol(accountId, symbol).filter(p -> "OPEN".equals(p.getStatus()))
                        .map(List::of).orElse(List.of())
                : positionRepository.findAllOpenByAccountId(accountId);
        if (targets.isEmpty()) {
            return new OpResult.Failed("нет открытых позиций" + (symbol != null ? " по " + symbol : ""));
        }
        ExecutionAdapter adapter = resolveAdapter(accountId);
        if (adapter == null) {
            return new OpResult.Failed("venue аккаунта '" + accountId + "' не подключена");
        }
        try {
            if (symbol != null) {
                adapter.closeAllPositions(symbol);
            } else {
                for (PositionEntity p : targets) {
                    adapter.closeAllPositions(p.getSymbol());
                }
            }
            return new OpResult.Ok("close-all отправлен для " + targets.size() + " позици(й), подтверждение придёт с поллером");
        } catch (RuntimeException e) {
            log.error("[close-all] {} failed: {}", accountId, e.toString());
            return new OpResult.Failed("ошибка close-all: " + e.getMessage());
        }
    }

    /** Cancel the old SL, place a new one at entry price, free the position's portfolio-risk slot (4.2). */
    public OpResult breakeven(Long positionId) {
        PositionEntity position = positionRepository.findById(positionId).orElse(null);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return new OpResult.Failed("позиция не найдена или не открыта");
        }
        return moveStopOrTp(position, "sl", position.getEntryPrice(), true);
    }

    /** Cancel the old SL or TP, place a new one at the given price. */
    public OpResult move(Long positionId, String type, BigDecimal price) {
        PositionEntity position = positionRepository.findById(positionId).orElse(null);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return new OpResult.Failed("позиция не найдена или не открыта");
        }
        if (!"sl".equalsIgnoreCase(type) && !"tp".equalsIgnoreCase(type)) {
            return new OpResult.Failed("type должен быть sl или tp");
        }
        return moveStopOrTp(position, type.toLowerCase(), price, false);
    }

    private OpResult moveStopOrTp(PositionEntity position, String type, BigDecimal price, boolean isBreakeven) {
        boolean wantsSl = "sl".equals(type);
        ExecutionAdapter adapter = resolveAdapter(position.getAccountId());
        if (adapter == null) {
            return new OpResult.Failed("venue аккаунта '" + position.getAccountId() + "' не подключена");
        }
        try {
            adapter.cancelReduceOnlyOrder(position.getSymbol(), wantsSl);
            if (wantsSl) {
                adapter.placeReduceOnlyStop(position.getSymbol(), position.getQuantity(), price);
            } else {
                adapter.placeReduceOnlyTakeProfit(position.getSymbol(), position.getQuantity(), price);
            }

            if (wantsSl) {
                position.setSlPrice(price.toPlainString());
                if (isBreakeven) {
                    position.setPlannedRiskAmount(null); // frees portfolio-risk headroom, 4.2
                }
            } else {
                position.setTpPrice(price.toPlainString());
            }
            positionRepository.save(position);

            return new OpResult.Ok((isBreakeven ? "стоп переставлен в безубыток " : (wantsSl ? "SL " : "TP ") + "переставлен на ")
                    + price);
        } catch (RuntimeException e) {
            log.error("[{}] move {} failed: {}", position.getAccountId(), type, e.toString());
            if (wantsSl) {
                // cancelReduceOnlyOrder already ran by the time placeReduceOnlyStop can throw — the
                // old stop may be gone and the new one never landed, leaving the position genuinely
                // unprotected. position.slPrice also wasn't updated (exception skipped that write),
                // so the poller's next verifyAndRepair checks a price that's already stale. A loud
                // notification here, not just OpResult.Failed to the operator who issued the command.
                notificationRepository.save(new NotificationEntity(NotificationEntity.MISSING_STOP,
                        position.getAccountId(), position.getSymbol(),
                        "перестановка стопа не удалась, позиция может быть без защиты: " + e.getMessage()));
            }
            return new OpResult.Failed("ошибка перестановки: " + e.getMessage());
        }
    }

    private ExecutionAdapter resolveAdapter(String accountId) {
        return accountRegistry.byId(accountId)
                .flatMap(account -> adapters.forExchange(account.exchange()))
                .orElse(null);
    }
}
