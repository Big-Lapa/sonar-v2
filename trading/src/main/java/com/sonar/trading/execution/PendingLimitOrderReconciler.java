package com.sonar.trading.execution;

import com.sonar.trading.i18n.MessageCode;
import com.sonar.trading.i18n.Msg;
import com.sonar.trading.config.AccountProps;
import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.entity.FillEntity;
import com.sonar.trading.domain.entity.NotificationEntity;
import com.sonar.trading.domain.entity.OrderCommandEntity;
import com.sonar.trading.domain.entity.OrderEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.entity.ProposalEntity;
import com.sonar.trading.domain.repository.FillRepository;
import com.sonar.trading.domain.repository.NotificationRepository;
import com.sonar.trading.domain.repository.OrderCommandRepository;
import com.sonar.trading.domain.repository.OrderRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.domain.repository.ProposalRepository;
import com.sonar.trading.exchange.dto.AdapterOrderResult;
import com.sonar.trading.exchange.model.AdapterOrderState;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A LIMIT entry (manual panel: order ticket's Limit toggle) can sit unfilled for any length of
 * time — {@link ProposalExecutionService#executeLeg} places it and moves on, reporting
 * {@code limitWorking} rather than blocking. Nothing else in the system was watching for it to
 * actually fill: {@link MinutePoller#pollAccount} only reconciles {@code PositionEntity} rows that
 * already exist, so a LIMIT order filling on the exchange never became a position at all (found in
 * PR #2 follow-up review — the code that shipped this claimed "the reconciliation poller picks
 * this up", which was never true; the real reconciliation service lives uncompiled in
 * {@code trading/src/_stage3-pending/}).
 *
 * <p>This is that missing piece, scoped to exactly the gap: poll every still-active OPEN-action
 * order once a minute, and once it reaches a terminal state, fold whatever actually filled into
 * {@code positions} (same weighted-average-on-add-on logic {@code executeLeg} uses) — decided by
 * {@code filledQty}, not by the state being exactly FILLED, because a LIMIT order can partially
 * fill and THEN be canceled (operator cancels from the orders tab, or the venue times it out); the
 * filled part is a real position on the exchange regardless of how the rest of the order ended.
 * Only a terminal state with nothing filled at all records no position. TP/SL is NOT re-attached here:
 * both venues accept {@code TpSlAttachment} on the LIMIT order at placement time and apply it once
 * the order fills, so protection is already live by the time this runs — this only verifies it,
 * the same as {@link StopGuard#verifyAndRepair} does for a MARKET fill.
 */
@Component
public class PendingLimitOrderReconciler {

    private static final Logger log = LoggerFactory.getLogger(PendingLimitOrderReconciler.class);

    private final AccountRegistry accountRegistry;
    private final ExecutionAdapterRegistry adapters;
    private final OrderRepository orderRepository;
    private final OrderCommandRepository orderCommandRepository;
    private final ProposalRepository proposalRepository;
    private final PositionRepository positionRepository;
    private final NotificationRepository notificationRepository;
    private final StopGuard stopGuard;
    private final FillRepository fillRepository;

    public PendingLimitOrderReconciler(AccountRegistry accountRegistry, ExecutionAdapterRegistry adapters,
                                       OrderRepository orderRepository, OrderCommandRepository orderCommandRepository,
                                       ProposalRepository proposalRepository, PositionRepository positionRepository,
                                       NotificationRepository notificationRepository, StopGuard stopGuard,
                                       FillRepository fillRepository) {
        this.accountRegistry = accountRegistry;
        this.adapters = adapters;
        this.orderRepository = orderRepository;
        this.orderCommandRepository = orderCommandRepository;
        this.proposalRepository = proposalRepository;
        this.positionRepository = positionRepository;
        this.notificationRepository = notificationRepository;
        this.stopGuard = stopGuard;
        this.fillRepository = fillRepository;
    }

    @Scheduled(fixedRate = 60_000)
    public void tick() {
        for (AccountProps account : accountRegistry.all()) {
            try {
                reconcileAccount(account);
            } catch (RuntimeException e) {
                log.error("[limit-reconcile] account {} tick failed: {}", account.id(), e.toString());
            }
        }
    }

    private void reconcileAccount(AccountProps account) {
        var pending = orderRepository.findAllActiveByAccountId(account.id()).stream()
                .filter(o -> "OPEN".equals(o.getAction()))
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        ExecutionAdapter adapter = adapters.forAccount(account.id()).orElse(null);
        if (adapter == null) {
            return;
        }
        for (OrderEntity order : pending) {
            reconcileOrder(account, adapter, order);
        }
    }

    private void reconcileOrder(AccountProps account, ExecutionAdapter adapter, OrderEntity order) {
        AdapterOrderResult result;
        try {
            result = adapter.getOrderStatus(order.getSymbol(), order.getClientOrderId());
        } catch (RuntimeException e) {
            log.warn("[limit-reconcile] {} getOrderStatus({}) failed: {}", account.id(), order.getClientOrderId(), e.toString());
            return;
        }
        if (result.state() == AdapterOrderState.NEW || result.state() == AdapterOrderState.PARTIALLY_FILLED) {
            return; // still resting — nothing to do yet, check again next tick
        }

        order.applyResult(result.exchangeOrderId(), result.state().name(), result.filledQty(),
                result.avgPrice(), result.fee(), null, null, System.currentTimeMillis());
        orderRepository.save(order);

        // Terminal state alone isn't enough to decide whether to fold a position: a LIMIT order
        // can be PARTIALLY filled and THEN reach a terminal state — cancel it after a partial fill
        // (operator cancels from the new orders tab, or the venue times it out) and Bybit reports
        // it as PartiallyFilledCanceled, which maps to plain CANCELED here (found in review: the
        // first version treated every non-FILLED terminal state as "nothing happened", discarding
        // whatever DID fill — the position sits live on the exchange with no row for it, same class
        // of bug this whole class exists to fix). filledQty, not state, decides whether anything
        // needs to be recorded; state only decided (above) whether to keep polling.
        BigDecimal filledQty = result.filledQty();
        if (filledQty == null || filledQty.signum() <= 0) {
            log.info("[limit-reconcile] {} {} order {} ended {} with nothing filled — nothing to create",
                    account.id(), order.getSymbol(), order.getClientOrderId(), result.state());
            return;
        }
        BigDecimal avgPrice = result.avgPrice();
        if (avgPrice == null) {
            log.error("[limit-reconcile] {} {} order {} filled {} but reports no avgPrice — cannot record fill",
                    account.id(), order.getSymbol(), order.getClientOrderId(), filledQty);
            return;
        }
        if (result.state() != AdapterOrderState.FILLED) {
            log.warn("[limit-reconcile] {} {} order {} ended {} after a partial fill of {} @ {} — folding the "
                            + "filled part into the position, the rest of the original qty never happened",
                    account.id(), order.getSymbol(), order.getClientOrderId(), result.state(), filledQty, avgPrice);
        }

        fillRepository.save(new FillEntity(order.getId(), account.id(), order.getSymbol(), filledQty, avgPrice,
                result.fee(), System.currentTimeMillis()));

        OrderCommandEntity command = orderCommandRepository.findByCommandId(order.getCommandId()).orElse(null);
        ProposalEntity proposal = command != null ? proposalRepository.findById(command.getProposalId()).orElse(null) : null;
        BigDecimal sl = proposal != null && proposal.getSlPrice() != null ? new BigDecimal(proposal.getSlPrice()) : null;
        BigDecimal tp = proposal != null && proposal.getTpPrice() != null ? new BigDecimal(proposal.getTpPrice()) : null;

        PositionEntity existingPosition = positionRepository.findByAccountIdAndSymbol(account.id(), order.getSymbol())
                .orElse(null);
        boolean isAddOn = existingPosition != null && "OPEN".equals(existingPosition.getStatus())
                && existingPosition.getEntryPrice() != null
                && existingPosition.getQuantity() != null && existingPosition.getQuantity().signum() > 0;

        PositionEntity position = existingPosition != null ? existingPosition
                : PositionEntity.newFlat(account.id(), order.getExchange(), order.getSymbol());
        BigDecimal finalQty = filledQty;
        BigDecimal finalEntry = avgPrice;
        if (isAddOn) {
            BigDecimal oldQty = existingPosition.getQuantity();
            finalQty = oldQty.add(filledQty);
            finalEntry = oldQty.multiply(existingPosition.getEntryPrice()).add(filledQty.multiply(avgPrice))
                    .divide(finalQty, 8, RoundingMode.HALF_UP);
        } else {
            position.setOpenedAt(System.currentTimeMillis());
        }
        position.setSide("LONG");
        position.setQuantity(finalQty);
        position.setEntryPrice(finalEntry);
        position.setStatus("OPEN");
        position.setUpdatedAt(System.currentTimeMillis());
        if (sl != null) {
            position.setSlPrice(sl.toPlainString());
        }
        if (tp != null) {
            position.setTpPrice(tp.toPlainString());
        }
        BigDecimal effectiveSl = position.getSlPrice() != null ? new BigDecimal(position.getSlPrice()) : null;
        BigDecimal riskAmount = effectiveSl != null ? finalQty.multiply(finalEntry.subtract(effectiveSl).abs()) : null;
        position.setPlannedRiskAmount(riskAmount != null ? riskAmount.toPlainString() : null);
        positionRepository.save(position);

        boolean stopVerified = stopGuard.hasStopOrder(account.id(), order.getSymbol());
        if (!stopVerified) {
            notificationRepository.save(new NotificationEntity(NotificationEntity.MISSING_STOP, account.id(), order.getSymbol(),
                    Msg.of(MessageCode.LIMIT_FILLED_NO_STOP, "qty", finalQty, "price", avgPrice)));
        }
        log.info("[limit-reconcile] {} {} LIMIT order {} filled {} @ {} — position now qty={} entry={} stopVerified={}",
                account.id(), order.getSymbol(), order.getClientOrderId(), filledQty, avgPrice, finalQty, finalEntry, stopVerified);
    }
}
