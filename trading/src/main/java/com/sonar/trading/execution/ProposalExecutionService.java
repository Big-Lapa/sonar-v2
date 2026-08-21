package com.sonar.trading.execution;

import com.sonar.trading.config.AccountProps;
import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.entity.OrderCommandEntity;
import com.sonar.trading.domain.entity.OrderEntity;
import com.sonar.trading.domain.entity.FillEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.entity.ProposalEntity;
import com.sonar.trading.domain.entity.SignalInboxEntity;
import com.sonar.trading.domain.repository.FillRepository;
import com.sonar.trading.domain.repository.OrderCommandRepository;
import com.sonar.trading.domain.repository.OrderRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.domain.repository.ProposalRepository;
import com.sonar.trading.domain.repository.SignalInboxRepository;
import com.sonar.trading.exchange.bingx.BingxFuturesClient;
import com.sonar.trading.exchange.bingx.BingxOrderMapper;
import com.sonar.trading.exchange.bingx.BingxOrderSide;
import com.sonar.trading.exchange.bingx.BingxOrderType;
import com.sonar.trading.exchange.bingx.BingxPlaceOrderRequest;
import com.sonar.trading.exchange.bingx.BingxPositionSide;
import com.sonar.trading.exchange.dto.AdapterOrderRequest;
import com.sonar.trading.exchange.dto.AdapterOrderResult;
import com.sonar.trading.exchange.dto.TpSlAttachment;
import com.sonar.trading.exchange.model.AdapterOrderSide;
import com.sonar.trading.exchange.model.AdapterOrderState;
import com.sonar.trading.exchange.model.AdapterOrderType;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.gateway.ExecutionGateway;
import com.sonar.trading.risk.AccountLegPreview;
import com.sonar.trading.risk.ProposalPreviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 4.6 execution flow, BingX-only for now (stage 3 — Binance execution is a separate future step,
 * parked in {@code trading/src/_stage3-pending/exchange/binance}). Confirming a proposal fans it
 * out across every tradable BingX account with a free slot: sizing + risk gates recomputed fresh
 * (not trusted from proposal-creation time), MARKET entry with attached absolute TP/SL, then a
 * best-effort stop-verification pass. A partial failure on one account is a normal outcome and
 * never rolls back the others.
 */
@Service
public class ProposalExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ProposalExecutionService.class);
    private static final BigDecimal MAX_PRICE_DRIFT = new BigDecimal("0.005"); // 0.5% (4.6 step 1)

    private final ProposalRepository proposals;
    private final AccountRegistry accountRegistry;
    private final ProposalPreviewService previewService;
    private final PositionRepository positionRepository;
    private final OrderCommandRepository orderCommandRepository;
    private final OrderRepository orderRepository;
    private final FillRepository fillRepository;
    private final SignalInboxRepository signalInboxRepository;
    private final ExecutionAdapter liveExecutionAdapter; // "liveExecutionAdapter" bean — BingX only
    private final ExecutionGateway executionGateway;      // for the standalone stop remediation order
    private final BingxFuturesClient bingxFuturesClient;  // read-only: price + open-orders verification
    private final ProposalClaimer claimer;

    public ProposalExecutionService(ProposalRepository proposals, AccountRegistry accountRegistry,
                                    ProposalPreviewService previewService, PositionRepository positionRepository,
                                    OrderCommandRepository orderCommandRepository, OrderRepository orderRepository,
                                    FillRepository fillRepository, SignalInboxRepository signalInboxRepository,
                                    ExecutionAdapter liveExecutionAdapter, ExecutionGateway executionGateway,
                                    BingxFuturesClient bingxFuturesClient, ProposalClaimer claimer) {
        this.proposals = proposals;
        this.accountRegistry = accountRegistry;
        this.previewService = previewService;
        this.positionRepository = positionRepository;
        this.orderCommandRepository = orderCommandRepository;
        this.orderRepository = orderRepository;
        this.fillRepository = fillRepository;
        this.signalInboxRepository = signalInboxRepository;
        this.liveExecutionAdapter = liveExecutionAdapter;
        this.executionGateway = executionGateway;
        this.bingxFuturesClient = bingxFuturesClient;
        this.claimer = claimer;
    }

    public sealed interface ConfirmOutcome {
        record Executed(List<ExecutionLegResult> legs) implements ConfirmOutcome {}
        record AlreadyProcessed(List<ExecutionLegResult> legs) implements ConfirmOutcome {}
        record Aborted(String reason) implements ConfirmOutcome {}
        record NotFound() implements ConfirmOutcome {}
    }

    public ConfirmOutcome confirm(String proposalId) {
        ProposalEntity proposal = proposals.findById(proposalId).orElse(null);
        if (proposal == null) {
            return new ConfirmOutcome.NotFound();
        }
        if (!"PENDING".equals(proposal.getStatus())) {
            return new ConfirmOutcome.AlreadyProcessed(rebuildReport(proposalId));
        }

        BigDecimal entry = new BigDecimal(proposal.getEntryPrice());
        BigDecimal sl = new BigDecimal(proposal.getSlPrice());
        BigDecimal tp = new BigDecimal(proposal.getTpPrice());

        String driftReason = checkPriceDrift(proposal.getSymbol(), entry);
        if (driftReason != null) {
            return new ConfirmOutcome.Aborted(driftReason);
        }

        if (!claimer.claim(proposalId)) {
            // Raced with a concurrent confirm — the other call owns execution.
            return new ConfirmOutcome.AlreadyProcessed(rebuildReport(proposalId));
        }

        List<AccountLegPreview> preview = previewService.preview(proposal.getSymbol(), entry, sl);
        List<ExecutionLegResult> results = new ArrayList<>();
        for (AccountLegPreview leg : preview) {
            results.add(executeLeg(proposal, leg, sl, tp));
        }
        return new ConfirmOutcome.Executed(results);
    }

    private String checkPriceDrift(String symbol, BigDecimal proposalEntry) {
        try {
            String bingxSymbol = BingxOrderMapper.toBingxSymbol(symbol);
            Map<String, Object> resp = bingxFuturesClient.getPrice(bingxSymbol, null);
            BigDecimal live = BingxOrderMapper.extractDecimal(resp, "price", "data.price");
            if (live == null || live.signum() <= 0) {
                return "не удалось получить текущую цену " + symbol + " — исполнение отменено";
            }
            BigDecimal drift = live.subtract(proposalEntry).abs()
                    .divide(proposalEntry, 10, java.math.RoundingMode.HALF_UP);
            if (drift.compareTo(MAX_PRICE_DRIFT) > 0) {
                return "цена ушла от предложения: entry=" + proposalEntry + " сейчас=" + live
                        + " drift=" + drift.multiply(new BigDecimal("100")) + "% (лимит 0.5%)";
            }
            return null;
        } catch (RuntimeException e) {
            log.error("checkPriceDrift failed for {}: {}", symbol, e.toString());
            return "биржа недоступна для проверки цены — исполнение отменено: " + e.getMessage();
        }
    }

    /**
     * NOT wrapped in a single DB transaction — the exchange call in the middle can't be inside
     * one anyway (a rollback can't undo a filled order), and self-invocation from {@link #confirm}
     * would bypass the Spring proxy regardless. Each {@code repository.save} commits on its own;
     * a crash mid-leg leaves partial rows (e.g. an OrderCommand with no matching Order) — the
     * stop-watchdog/reconciliation poller (stage 4) is what cleans that up, same as v1's design.
     */
    private ExecutionLegResult executeLeg(ProposalEntity proposal, AccountLegPreview leg,
                                          BigDecimal sl, BigDecimal tp) {
        String accountId = leg.accountId();
        if (!leg.approved()) {
            return ExecutionLegResult.failed(accountId, leg.rejectReason());
        }
        AccountProps account = accountRegistry.byId(accountId).orElse(null);
        if (account == null) {
            return ExecutionLegResult.failed(accountId, "аккаунт не найден в реестре");
        }
        if (!"bingx".equalsIgnoreCase(account.exchange())) {
            return ExecutionLegResult.failed(accountId,
                    "venue '" + account.exchange() + "' не подключена в этой версии (пока только BingX)");
        }

        String idempotencyKey = proposal.getProposalId();
        if (signalInboxRepository.findByIdempotencyKeyAndAccountId(idempotencyKey, accountId).isPresent()) {
            return ExecutionLegResult.failed(accountId, "уже обрабатывалось (idempotency)");
        }
        signalInboxRepository.save(new SignalInboxEntity(idempotencyKey, accountId, idempotencyKey,
                System.currentTimeMillis()));

        String clientOrderId = shortId(proposal.getProposalId()) + "-" + accountId;
        String commandId = UUID.randomUUID().toString();
        orderCommandRepository.save(new OrderCommandEntity(commandId, accountId, proposal.getProposalId(),
                clientOrderId, "BINGX", proposal.getSymbol(), "LONG", "OPEN", leg.qty(), "MARKET",
                System.currentTimeMillis()));

        OrderEntity order = new OrderEntity(clientOrderId, commandId, accountId, "BINGX", proposal.getSymbol(),
                "LONG", "OPEN", leg.qty(), System.currentTimeMillis());
        order = orderRepository.save(order);

        AdapterOrderRequest request = new AdapterOrderRequest(proposal.getSymbol(), AdapterOrderSide.BUY,
                AdapterOrderType.MARKET, leg.qty(), null, clientOrderId, OrderIntent.ENTRY, null,
                new TpSlAttachment(tp, sl));

        AdapterOrderResult result;
        try {
            result = liveExecutionAdapter.placeOrder(request);
        } catch (RuntimeException e) {
            log.error("[{}] placeOrder failed for {}: {}", accountId, proposal.getSymbol(), e.toString());
            order.applyResult(null, "REJECTED", null, null, null, "PLACE_FAILED", e.getMessage(),
                    System.currentTimeMillis());
            orderRepository.save(order);
            markInboxDone(idempotencyKey, accountId);
            return ExecutionLegResult.failed(accountId, "ошибка размещения ордера: " + e.getMessage());
        }

        int attempts = 0;
        while (result.state() != AdapterOrderState.FILLED
                && result.state() != AdapterOrderState.REJECTED
                && result.state() != AdapterOrderState.CANCELED
                && result.state() != AdapterOrderState.FAILED
                && attempts < 5) {
            sleep(1000);
            attempts++;
            try {
                result = liveExecutionAdapter.getOrderStatus(proposal.getSymbol(), clientOrderId);
            } catch (RuntimeException e) {
                log.warn("[{}] getOrderStatus attempt {} failed: {}", accountId, attempts, e.toString());
            }
        }

        order.applyResult(result.exchangeOrderId(), result.state().name(), result.filledQty(),
                result.avgPrice(), result.fee(), null, null, System.currentTimeMillis());
        orderRepository.save(order);

        if (result.state() != AdapterOrderState.FILLED) {
            markInboxDone(idempotencyKey, accountId);
            return ExecutionLegResult.failed(accountId,
                    "ордер не подтвердился как FILLED за 5 попыток (state=" + result.state() + ")");
        }

        BigDecimal filledQty = result.filledQty() != null ? result.filledQty() : leg.qty();
        BigDecimal avgPrice = result.avgPrice() != null ? result.avgPrice() : new BigDecimal(proposal.getEntryPrice());

        fillRepository.save(new FillEntity(order.getId(), accountId, proposal.getSymbol(), filledQty, avgPrice,
                result.fee(), System.currentTimeMillis()));

        PositionEntity position = positionRepository.findByAccountIdAndSymbol(accountId, proposal.getSymbol())
                .orElseGet(() -> PositionEntity.newFlat(accountId, "BINGX", proposal.getSymbol()));
        position.setSide("LONG");
        position.setQuantity(filledQty);
        position.setEntryPrice(avgPrice);
        position.setStatus("OPEN");
        position.setOpenedAt(System.currentTimeMillis());
        position.setUpdatedAt(System.currentTimeMillis());
        position.setSlPrice(sl.toPlainString());
        position.setTpPrice(tp.toPlainString());
        position.setPlannedRiskAmount(leg.riskUsd() != null ? leg.riskUsd().toPlainString() : null);
        positionRepository.save(position);

        boolean stopVerified = verifyAndRepairStop(proposal.getSymbol(), filledQty, sl);

        markInboxDone(idempotencyKey, accountId);
        return ExecutionLegResult.filled(accountId, filledQty, avgPrice, sl, tp, leg.riskUsd(), stopVerified);
    }

    /**
     * 4.6 step 5 / 3rd invariant: BingX attaches TP/SL atomically to the entry order, but we
     * verify it actually landed rather than trusting the attach silently succeeded. One
     * remediation attempt (a standalone reduce-only STOP_MARKET) if it's missing; a still-missing
     * stop after that is surfaced in the report — full Telegram escalation is stage 4/6.
     */
    private boolean verifyAndRepairStop(String symbol, BigDecimal qty, BigDecimal slPrice) {
        String bingxSymbol = BingxOrderMapper.toBingxSymbol(symbol);
        try {
            if (hasStopOrder(bingxSymbol)) {
                return true;
            }
            log.error("[STOP-MISSING] no conditional stop found for {} after entry — placing remediation order",
                    bingxSymbol);
            BingxPlaceOrderRequest remediation = new BingxPlaceOrderRequest(bingxSymbol, BingxOrderSide.SELL,
                    BingxPositionSide.BOTH, BingxOrderType.STOP_MARKET, qty.stripTrailingZeros().toPlainString(),
                    null, null, "stop-repair-" + UUID.randomUUID(), null, null,
                    slPrice.stripTrailingZeros().toPlainString(), true);
            executionGateway.placeOrder(remediation, OrderIntent.CLOSE_REDUCE);
            return hasStopOrder(bingxSymbol);
        } catch (RuntimeException e) {
            log.error("[STOP-MISSING] remediation failed for {}: {}", bingxSymbol, e.toString());
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean hasStopOrder(String bingxSymbol) {
        Map<String, Object> resp = bingxFuturesClient.getOpenOrders(bingxSymbol);
        Object data = resp.get("data");
        List<Map<String, Object>> orders;
        if (data instanceof List<?> list) {
            orders = (List<Map<String, Object>>) list;
        } else if (data instanceof Map<?, ?> m && m.get("orders") instanceof List<?> list) {
            orders = (List<Map<String, Object>>) list;
        } else {
            return false;
        }
        return orders.stream().anyMatch(o -> {
            String type = String.valueOf(o.getOrDefault("type", "")).toUpperCase();
            return type.contains("STOP");
        });
    }

    private void markInboxDone(String idempotencyKey, String accountId) {
        signalInboxRepository.findByIdempotencyKeyAndAccountId(idempotencyKey, accountId).ifPresent(row -> {
            row.setStatus(SignalInboxEntity.DONE);
            row.setProcessedAt(System.currentTimeMillis());
            signalInboxRepository.save(row);
        });
    }

    private List<ExecutionLegResult> rebuildReport(String proposalId) {
        List<ExecutionLegResult> out = new ArrayList<>();
        for (OrderCommandEntity cmd : orderCommandRepository.findByProposalId(proposalId)) {
            orderRepository.findByClientOrderId(cmd.getClientOrderId()).ifPresentOrElse(order -> {
                if ("FILLED".equals(order.getStatus())) {
                    out.add(ExecutionLegResult.filled(cmd.getAccountId(), order.getFilledQuantity(),
                            order.getAvgFillPrice(), null, null, null, true));
                } else {
                    out.add(ExecutionLegResult.failed(cmd.getAccountId(),
                            "ранее завершилось со статусом " + order.getStatus()));
                }
            }, () -> out.add(ExecutionLegResult.failed(cmd.getAccountId(), "ордер не найден")));
        }
        return out;
    }

    private static String shortId(String proposalId) {
        return proposalId.length() > 12 ? proposalId.substring(0, 12) : proposalId;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
