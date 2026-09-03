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
import com.sonar.trading.exchange.dto.AdapterOrderRequest;
import com.sonar.trading.exchange.dto.AdapterOrderResult;
import com.sonar.trading.exchange.dto.TpSlAttachment;
import com.sonar.trading.exchange.model.AdapterOrderSide;
import com.sonar.trading.exchange.model.AdapterOrderState;
import com.sonar.trading.exchange.model.AdapterOrderType;
import com.sonar.trading.exchange.model.OrderIntent;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import com.sonar.trading.risk.AccountLegPreview;
import com.sonar.trading.risk.ProposalPreviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 4.6 execution flow — full multi-venue routing (grilled plan): confirming a proposal fans it out
 * across every tradable account with a free slot, on whichever venue that account is wired to
 * ({@link ExecutionAdapterRegistry}, resolved per {@link AccountProps#exchange()}), not just
 * BingX. Binance still isn't wired (parked in {@code _stage3-pending}) — an account on any venue
 * without a registered adapter fails that one leg with a clear reason, same as before.
 *
 * <p>Sizing + risk gates are recomputed fresh per leg (not trusted from proposal-creation time),
 * MARKET entry with attached absolute TP/SL, then a best-effort stop-verification pass. A partial
 * failure on one account is a normal outcome and never rolls back the others.
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
    private final ExecutionAdapterRegistry adapters;
    private final StopGuard stopGuard;
    private final ProposalClaimer claimer;

    public ProposalExecutionService(ProposalRepository proposals, AccountRegistry accountRegistry,
                                    ProposalPreviewService previewService, PositionRepository positionRepository,
                                    OrderCommandRepository orderCommandRepository, OrderRepository orderRepository,
                                    FillRepository fillRepository, SignalInboxRepository signalInboxRepository,
                                    ExecutionAdapterRegistry adapters, StopGuard stopGuard, ProposalClaimer claimer) {
        this.proposals = proposals;
        this.accountRegistry = accountRegistry;
        this.previewService = previewService;
        this.positionRepository = positionRepository;
        this.orderCommandRepository = orderCommandRepository;
        this.orderRepository = orderRepository;
        this.fillRepository = fillRepository;
        this.signalInboxRepository = signalInboxRepository;
        this.adapters = adapters;
        this.stopGuard = stopGuard;
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
            log.warn("[{}] confirm aborted: {}", proposalId, driftReason);
            return new ConfirmOutcome.Aborted(driftReason);
        }

        if (!claimer.claim(proposalId)) {
            // Raced with a concurrent confirm — the other call owns execution.
            return new ConfirmOutcome.AlreadyProcessed(rebuildReport(proposalId));
        }

        BigDecimal manualNotional = proposal.getNotional() != null ? new BigDecimal(proposal.getNotional()) : null;
        List<AccountLegPreview> preview = previewService.preview(proposal.getSymbol(), entry, sl,
                proposal.getAccountId(), manualNotional);
        List<ExecutionLegResult> results = new ArrayList<>();
        for (AccountLegPreview leg : preview) {
            results.add(executeLeg(proposal, leg, sl, tp));
        }
        return new ConfirmOutcome.Executed(results);
    }

    /**
     * Coarse sanity gate before any account/venue is even known (preview() runs after this) — so
     * this can't yet ask "the accounts this proposal will actually execute on". Deliberately NOT
     * {@code adapters.any()}: that picks an arbitrary wired venue via unordered map iteration, and
     * a LIVE Bybit adapter is always registered (even with an empty API key — its price read is
     * unsigned) — a random pick could silently price against Bybit's testnet quotes instead of the
     * account's real venue, which at the 0.5% drift threshold means false aborts or false passes.
     * Picking the FIRST account in {@link AccountRegistry} (stable env-var order,
     * {@code TRADING_ACCOUNTS_0}, {@code _1}, ...) is deterministic across restarts and matches
     * pre-multi-venue behaviour (the only account/venue that existed was always account 0's BingX).
     */
    private String checkPriceDrift(String symbol, BigDecimal proposalEntry) {
        ExecutionAdapter adapter = accountRegistry.all().stream()
                .flatMap(a -> adapters.forExchange(a.exchange()).stream())
                .findFirst()
                .orElse(null);
        if (adapter == null) {
            return "нет подключённой биржи для проверки цены — исполнение отменено";
        }
        try {
            BigDecimal live = adapter.getMarkPrice(symbol);
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
        ExecutionAdapter adapter = adapters.forExchange(account.exchange()).orElse(null);
        if (adapter == null) {
            return ExecutionLegResult.failed(accountId,
                    "venue '" + account.exchange() + "' не подключена в этой версии");
        }
        String exchangeLabel = account.exchange().toUpperCase(java.util.Locale.ROOT);

        String idempotencyKey = proposal.getProposalId();
        if (signalInboxRepository.findByIdempotencyKeyAndAccountId(idempotencyKey, accountId).isPresent()) {
            return ExecutionLegResult.failed(accountId, "уже обрабатывалось (idempotency)");
        }
        signalInboxRepository.save(new SignalInboxEntity(idempotencyKey, accountId, idempotencyKey,
                System.currentTimeMillis()));

        String clientOrderId = shortId(proposal.getProposalId()) + "-" + accountId;
        String commandId = UUID.randomUUID().toString();
        orderCommandRepository.save(new OrderCommandEntity(commandId, accountId, proposal.getProposalId(),
                clientOrderId, exchangeLabel, proposal.getSymbol(), "LONG", "OPEN", leg.qty(), "MARKET",
                System.currentTimeMillis()));

        OrderEntity order = new OrderEntity(clientOrderId, commandId, accountId, exchangeLabel, proposal.getSymbol(),
                "LONG", "OPEN", leg.qty(), System.currentTimeMillis());
        order = orderRepository.save(order);

        AdapterOrderRequest request = new AdapterOrderRequest(proposal.getSymbol(), AdapterOrderSide.BUY,
                AdapterOrderType.MARKET, leg.qty(), null, clientOrderId, OrderIntent.ENTRY, null,
                new TpSlAttachment(tp, sl), proposal.getLeverage());

        AdapterOrderResult result;
        try {
            result = adapter.placeOrder(request);
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
                result = adapter.getOrderStatus(proposal.getSymbol(), clientOrderId);
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
                .orElseGet(() -> PositionEntity.newFlat(accountId, exchangeLabel, proposal.getSymbol()));
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

        // 4.6 step 5 / 3rd invariant: the venue attaches TP/SL atomically at entry, but we verify
        // it actually landed rather than trusting the attach silently succeeded.
        boolean stopVerified = stopGuard.verifyAndRepair(account.exchange(), proposal.getSymbol(), filledQty, sl);

        markInboxDone(idempotencyKey, accountId);
        return ExecutionLegResult.filled(accountId, filledQty, avgPrice, sl, tp, leg.riskUsd(), stopVerified);
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
