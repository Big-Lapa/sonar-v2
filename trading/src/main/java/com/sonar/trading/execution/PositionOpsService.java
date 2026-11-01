package com.sonar.trading.execution;

import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.entity.NotificationEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.repository.NotificationRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.exchange.dto.AdapterOrderRequest;
import com.sonar.trading.i18n.MessageCode;
import com.sonar.trading.i18n.Msg;
import com.sonar.trading.exchange.dto.AdapterOrderResult;
import com.sonar.trading.exchange.model.AdapterOrderSide;
import com.sonar.trading.exchange.model.AdapterOrderType;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import com.sonar.trading.reconciliation.AccountReaderRegistry;
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
    private final AccountReaderRegistry accountReaders;

    public PositionOpsService(PositionRepository positionRepository, AccountRegistry accountRegistry,
                              ExecutionAdapterRegistry adapters, NotificationRepository notificationRepository,
                              QuantityNormalizer quantityNormalizer, AccountReaderRegistry accountReaders) {
        this.positionRepository = positionRepository;
        this.accountRegistry = accountRegistry;
        this.adapters = adapters;
        this.notificationRepository = notificationRepository;
        this.quantityNormalizer = quantityNormalizer;
        this.accountReaders = accountReaders;
    }

    public sealed interface OpResult {
        record Ok(String code, java.util.Map<String, String> params, String message) implements OpResult {}
        record Failed(String code, java.util.Map<String, String> params, String reason) implements OpResult {}

        static Ok ok(Msg msg) { return new Ok(msg.code().name(), msg.params(), msg.text()); }
        static Failed failed(Msg msg) { return new Failed(msg.code().name(), msg.params(), msg.text()); }
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
            return OpResult.failed(Msg.of(MessageCode.NO_OPEN_POSITIONS, "symbol", symbol));
        }
        ExecutionAdapter adapter = resolveAdapter(accountId);
        if (adapter == null) {
            return OpResult.failed(Msg.of(MessageCode.VENUE_NOT_CONNECTED, "account", accountId));
        }
        try {
            if (symbol != null) {
                adapter.closeAllPositions(symbol);
            } else {
                for (PositionEntity p : targets) {
                    adapter.closeAllPositions(p.getSymbol());
                }
            }
            return OpResult.ok(Msg.of(MessageCode.CLOSE_ALL_SENT, "count", targets.size()));
        } catch (RuntimeException e) {
            log.error("[close-all] {} failed: {}", accountId, e.toString());
            return OpResult.failed(Msg.of(MessageCode.CLOSE_ALL_FAILED, "error", e.getMessage()));
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
            return OpResult.failed(Msg.of(MessageCode.POSITION_NOT_FOUND));
        }
        if (qty == null || qty.signum() <= 0) {
            return OpResult.failed(Msg.of(MessageCode.QTY_MUST_BE_POSITIVE));
        }
        boolean limit = "LIMIT".equalsIgnoreCase(orderType);
        if (limit && (price == null || price.signum() <= 0)) {
            return OpResult.failed(Msg.of(MessageCode.PRICE_REQUIRED_FOR_LIMIT));
        }

        var account = accountRegistry.byId(position.getAccountId()).orElse(null);
        ExecutionAdapter adapter = account != null ? adapters.forAccount(account.id()).orElse(null) : null;
        if (adapter == null) {
            return OpResult.failed(Msg.of(MessageCode.VENUE_NOT_CONNECTED, "account", position.getAccountId()));
        }

        // Clamp against the LIVE exchange qty, not the DB row: the DB is updated by the minute
        // poller (lags up to 60s) and closePartial deliberately doesn't touch it, so two fast
        // closes back-to-back (or a resting limit-close plus a second close) would otherwise both
        // clamp against the same stale qty and together send reduce-only for more than the actual
        // position — on a venue where reduceOnly is enforced loosely for limit orders that can flip
        // the position instead of just failing.
        //
        // KNOWN GAP (PR #2 follow-up review): forExchange resolves ONE reader per VENUE, not per
        // ACCOUNT — inherited from the same pattern every adapter lookup in this codebase already
        // uses (accountRegistry.byId(...).exchange() -> one shared client per exchange). With today's
        // one-account-per-exchange config it can't misfire, but a second account on the same venue
        // would read that OTHER account's live qty here. Fixing this for real means per-account API
        // client wiring throughout the execution layer, not a local patch to this one clamp.
        BigDecimal liveQty = accountReaders.forAccount(account.id())
                .map(reader -> reader.getPositions().stream()
                        .filter(p -> position.getSymbol().equals(p.symbol()) && !p.isFlat())
                        .findFirst().map(p -> p.qty()).orElse(BigDecimal.ZERO))
                .orElse(position.getQuantity());
        if (liveQty.signum() <= 0) {
            return OpResult.failed(Msg.of(MessageCode.POSITION_ALREADY_CLOSED));
        }
        BigDecimal clampedQty = qty.min(liveQty);

        // Floor (never bump up) to the exchange's lot step — a requested %/qty that rounds down
        // below one step must fail loudly, not silently close more (or nothing at all, which is
        // what most exchanges do when they just reject a misaligned qty outright).
        BigDecimal normalizedQty = quantityNormalizer.floorForClose(account.id(), position.getSymbol(),
                clampedQty, price != null ? price : position.getEntryPrice());
        if (normalizedQty == null) {
            return OpResult.failed(Msg.of(MessageCode.QTY_BELOW_LOT_STEP, "qty", clampedQty, "symbol", position.getSymbol()));
        }
        clampedQty = normalizedQty;
        try {
            AdapterOrderRequest request = new AdapterOrderRequest(position.getSymbol(), AdapterOrderSide.SELL,
                    limit ? AdapterOrderType.LIMIT : AdapterOrderType.MARKET, clampedQty,
                    limit ? price : null, "cl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20),
                    OrderIntent.CLOSE_REDUCE);
            AdapterOrderResult result = adapter.placeOrder(request);

            // BingX's SL/TP are separate reduce-only orders still sized to the pre-close qty —
            // resize them to what remains so they don't guard a bigger volume than actually exists
            // (same asymmetry ProposalExecutionService's add-on path already handles for entries).
            // Bybit's stop is position-level and shrinks with the position automatically.
            BigDecimal remaining = liveQty.subtract(clampedQty);
            if ("bingx".equalsIgnoreCase(account.exchange()) && remaining.signum() > 0
                    && position.getSlPrice() != null && position.getTpPrice() != null) {
                try {
                    adapter.cancelReduceOnlyOrder(position.getSymbol(), true);
                    adapter.cancelReduceOnlyOrder(position.getSymbol(), false);
                    adapter.placeReduceOnlyStop(position.getSymbol(), remaining, new BigDecimal(position.getSlPrice()));
                    adapter.placeReduceOnlyTakeProfit(position.getSymbol(), remaining, new BigDecimal(position.getTpPrice()));
                } catch (RuntimeException e) {
                    log.error("[{}] SL/TP resize after partial close failed for {}: {}",
                            position.getAccountId(), position.getSymbol(), e.toString());
                    notificationRepository.save(new NotificationEntity(NotificationEntity.MISSING_STOP,
                            position.getAccountId(), position.getSymbol(),
                            Msg.of(MessageCode.PARTIAL_CLOSE_STOPS_NOT_RESCALED, "error", e.getMessage())));
                }
            }

            return OpResult.ok(Msg.of(limit ? MessageCode.CLOSE_LIMIT_SENT : MessageCode.CLOSE_MARKET_SENT,
                    "qty", clampedQty, "symbol", position.getSymbol(), "state", result.state()));
        } catch (RuntimeException e) {
            log.error("[{}] closePartial {} {} failed: {}", position.getAccountId(), position.getSymbol(), clampedQty, e.toString());
            return OpResult.failed(Msg.of(MessageCode.CLOSE_FAILED, "error", e.getMessage()));
        }
    }

    /** Cancel the old SL, place a new one at entry price, free the position's portfolio-risk slot (4.2). */
    public OpResult breakeven(Long positionId) {
        PositionEntity position = positionRepository.findById(positionId).orElse(null);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return OpResult.failed(Msg.of(MessageCode.POSITION_NOT_FOUND));
        }
        return moveStopOrTp(position, "sl", position.getEntryPrice(), true);
    }

    /** Cancel the old SL or TP, place a new one at the given price. */
    public OpResult move(Long positionId, String type, BigDecimal price) {
        PositionEntity position = positionRepository.findById(positionId).orElse(null);
        if (position == null || !"OPEN".equals(position.getStatus())) {
            return OpResult.failed(Msg.of(MessageCode.POSITION_NOT_FOUND));
        }
        if (!"sl".equalsIgnoreCase(type) && !"tp".equalsIgnoreCase(type)) {
            return OpResult.failed(Msg.of(MessageCode.STOP_TYPE_INVALID));
        }
        return moveStopOrTp(position, type.toLowerCase(), price, false);
    }

    private OpResult moveStopOrTp(PositionEntity position, String type, BigDecimal price, boolean isBreakeven) {
        boolean wantsSl = "sl".equals(type);
        ExecutionAdapter adapter = resolveAdapter(position.getAccountId());
        if (adapter == null) {
            return OpResult.failed(Msg.of(MessageCode.VENUE_NOT_CONNECTED, "account", position.getAccountId()));
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

            return OpResult.ok(Msg.of(isBreakeven ? MessageCode.STOP_MOVED_BREAKEVEN
                    : (wantsSl ? MessageCode.SL_MOVED : MessageCode.TP_MOVED), "price", price));
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
                        Msg.of(MessageCode.STOP_MOVE_UNPROTECTED, "error", e.getMessage())));
            }
            return OpResult.failed(Msg.of(MessageCode.STOP_MOVE_FAILED, "error", e.getMessage()));
        }
    }

    private ExecutionAdapter resolveAdapter(String accountId) {
        return accountRegistry.byId(accountId)
                .flatMap(account -> adapters.forAccount(account.id()))
                .orElse(null);
    }
}
