package com.sonar.trading.execution;

import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.entity.NotificationEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.repository.NotificationRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.exchange.dto.AdapterOrderRequest;
import com.sonar.trading.exchange.dto.AdapterOrderResult;
import com.sonar.trading.exchange.model.AdapterOrderSide;
import com.sonar.trading.exchange.model.AdapterOrderType;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import com.sonar.trading.sizing.QuantityNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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
    private final QuantityNormalizer quantityNormalizer;

    public PositionOpsService(PositionRepository positionRepository, AccountRegistry accountRegistry,
                              ExecutionAdapterRegistry adapters, NotificationRepository notificationRepository,
                              QuantityNormalizer quantityNormalizer) {
        this.positionRepository = positionRepository;
        this.accountRegistry = accountRegistry;
        this.adapters = adapters;
        this.notificationRepository = notificationRepository;
        this.quantityNormalizer = quantityNormalizer;
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

    /**
     * Manual trading panel: close an arbitrary quantity of an open position (market or limit),
     * not necessarily the whole thing. Unlike {@link #closeAll} (venue-native bulk / synthesized
     * close-all), this always goes through {@link ExecutionAdapter#placeOrder} with a reduce-only
     * {@link OrderIntent#CLOSE_REDUCE} SELL — the same primitive an entry uses, just the other
     * side, and it already handles reduceOnly correctly per venue. A 100%-of-qty request is not
     * special-cased: it's still just a reduce-only order for the full size, so there is exactly
     * one code path for "close some" and "close all of this position" (as opposed to the account
     * -wide {@code closeAll}, which stays the dedicated bulk/verified path for /closeall).
     *
     * <p>{@code qty} is clamped to the position's own quantity — a caller-computed percentage that
     * rounds up past 100% (or a stale qty from a UI that hasn't refreshed) must not overshoot into
     * a reduce-only order the exchange rejects, or worse, one that's accepted and flips the
     * position on a venue where reduceOnly is advisory. DB status is NOT flipped here, same
     * reasoning as {@code closeAll}: the poller is the single source of truth for the fill.
     */
    public OpResult closePartial(Long positionId, String orderType, BigDecimal qty, BigDecimal price) {
        PositionEntity position = positionRepository.findById(positionId).orElse(null);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return new OpResult.Failed("позиция не найдена или не открыта");
        }
        if (qty == null || qty.signum() <= 0) {
            return new OpResult.Failed("qty должен быть положительным");
        }
        boolean limit = "LIMIT".equalsIgnoreCase(orderType);
        if (limit && (price == null || price.signum() <= 0)) {
            return new OpResult.Failed("price обязателен для limit-закрытия");
        }
        BigDecimal clampedQty = qty.min(position.getQuantity());

        var account = accountRegistry.byId(position.getAccountId()).orElse(null);
        ExecutionAdapter adapter = account != null ? adapters.forExchange(account.exchange()).orElse(null) : null;
        if (adapter == null) {
            return new OpResult.Failed("venue аккаунта '" + position.getAccountId() + "' не подключена");
        }
        // Floor (never bump up) to the exchange's lot step — a requested %/qty that rounds down
        // below one step must fail loudly, not silently close more (or nothing at all, which is
        // what most exchanges do when they just reject a misaligned qty outright).
        BigDecimal normalizedQty = quantityNormalizer.floorForClose(account.exchange(), position.getSymbol(), clampedQty);
        if (normalizedQty == null) {
            return new OpResult.Failed("qty " + clampedQty + " меньше шага лота биржи для " + position.getSymbol());
        }
        clampedQty = normalizedQty;
        try {
            AdapterOrderRequest request = new AdapterOrderRequest(position.getSymbol(), AdapterOrderSide.SELL,
                    limit ? AdapterOrderType.LIMIT : AdapterOrderType.MARKET, clampedQty,
                    limit ? price : null, "cl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                    OrderIntent.CLOSE_REDUCE);
            AdapterOrderResult result = adapter.placeOrder(request);
            return new OpResult.Ok((limit ? "limit-заявка на закрытие " : "закрытие market ")
                    + clampedQty + " " + position.getSymbol() + " отправлено (state=" + result.state() + ")"
                    + ", подтверждение придёт с поллером");
        } catch (RuntimeException e) {
            log.error("[{}] closePartial {} {} failed: {}", position.getAccountId(), position.getSymbol(), clampedQty, e.toString());
            return new OpResult.Failed("ошибка закрытия: " + e.getMessage());
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
                // What the position is left carrying depends on the venue: where the stop is a
                // separate order (BingX) cancelReduceOnlyOrder already removed the old one and the
                // position is genuinely unprotected; where it is position state (Bybit) the old stop
                // survives at its old price. Either way position.slPrice wasn't updated (the
                // exception skipped that write), so what the operator asked for did not happen and
                // the poller will keep verifying against the pre-move price. Loud either way, not
                // just an OpResult.Failed to whoever typed the command.
                notificationRepository.save(new NotificationEntity(NotificationEntity.MISSING_STOP,
                        position.getAccountId(), position.getSymbol(),
                        "перестановка стопа не удалась, проверить защиту позиции: " + e.getMessage()));
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
