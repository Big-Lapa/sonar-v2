package com.sonar.trading.execution;

import com.sonar.trading.config.AccountProps;
import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.entity.AccountRiskEntity;
import com.sonar.trading.domain.entity.DailyBaselineEntity;
import com.sonar.trading.domain.entity.OrderCommandEntity;
import com.sonar.trading.domain.entity.OrderEntity;
import com.sonar.trading.domain.entity.FillEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.entity.ProposalEntity;
import com.sonar.trading.domain.entity.SignalInboxEntity;
import com.sonar.trading.domain.repository.AccountRiskRepository;
import com.sonar.trading.domain.repository.DailyBaselineRepository;
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
import com.sonar.trading.risk.AccountRiskProfile;
import com.sonar.trading.risk.ProposalPreviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
    private final AccountRiskRepository accountRiskRepository;
    private final DailyBaselineRepository dailyBaselineRepository;

    public ProposalExecutionService(ProposalRepository proposals, AccountRegistry accountRegistry,
                                    ProposalPreviewService previewService, PositionRepository positionRepository,
                                    OrderCommandRepository orderCommandRepository, OrderRepository orderRepository,
                                    FillRepository fillRepository, SignalInboxRepository signalInboxRepository,
                                    ExecutionAdapterRegistry adapters, StopGuard stopGuard, ProposalClaimer claimer,
                                    AccountRiskRepository accountRiskRepository,
                                    DailyBaselineRepository dailyBaselineRepository) {
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
        this.accountRiskRepository = accountRiskRepository;
        this.dailyBaselineRepository = dailyBaselineRepository;
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

        // The drift gate exists to catch a stale MARKET confirm (price moved since preview) — it
        // doesn't apply to a LIMIT entry, whose whole point is a price deliberately away from
        // market; comparing it to the live price would abort perfectly normal limit orders.
        boolean isLimit = "LIMIT".equals(proposal.getOrderType());
        if (!isLimit) {
            String driftReason = checkPriceDrift(proposal.getSymbol(), entry);
            if (driftReason != null) {
                log.warn("[{}] confirm aborted: {}", proposalId, driftReason);
                return new ConfirmOutcome.Aborted(driftReason);
            }
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

        // Adding to an already-open position ("доливка"): the incremental entry order carries no
        // TP/SL attachment. Bybit's stop is position-level and already covers the combined qty
        // untouched; BingX's attachment would otherwise create a SECOND reduce-only order sized to
        // just this increment, leaving the original stop covering only the old qty. The BingX
        // resize (cancel old, place one fresh pair sized to the new total) happens below, after
        // the fill, once the combined qty is known.
        //
        // entryPrice/quantity null-guarded: a reused-but-broken row (e.g. a crash mid add-on left a
        // DB write pending — see the class javadoc) must fall back to treating this as a fresh open
        // rather than NPE deep inside the weighted-average math below, after the exchange order has
        // already filled.
        PositionEntity existingPosition = positionRepository.findByAccountIdAndSymbol(accountId, proposal.getSymbol())
                .orElse(null);
        boolean isAddOn = existingPosition != null && "OPEN".equals(existingPosition.getStatus())
                && existingPosition.getEntryPrice() != null
                && existingPosition.getQuantity() != null && existingPosition.getQuantity().signum() > 0;

        // Cap the position's COMBINED notional/leverage against the account's own caps, not just
        // this leg's — sizeManual/size only ever clamp the notional of the single order being
        // sized, so N add-ons at the per-order cap would otherwise sum to N times the account limit
        // with nothing in the pipeline ever noticing.
        if (isAddOn) {
            String rejectReason = checkAddOnCaps(accountId, existingPosition, leg.qty(), new BigDecimal(proposal.getEntryPrice()));
            if (rejectReason != null) {
                return ExecutionLegResult.failed(accountId, rejectReason);
            }
        }

        String idempotencyKey = proposal.getProposalId();
        if (signalInboxRepository.findByIdempotencyKeyAndAccountId(idempotencyKey, accountId).isPresent()) {
            return ExecutionLegResult.failed(accountId, "уже обрабатывалось (idempotency)");
        }
        signalInboxRepository.save(new SignalInboxEntity(idempotencyKey, accountId, idempotencyKey,
                System.currentTimeMillis()));

        boolean isLimit = "LIMIT".equals(proposal.getOrderType());
        BigDecimal limitPrice = isLimit ? new BigDecimal(proposal.getLimitPrice()) : null;

        String clientOrderId = shortId(proposal.getProposalId()) + "-" + accountId;
        String commandId = UUID.randomUUID().toString();
        orderCommandRepository.save(new OrderCommandEntity(commandId, accountId, proposal.getProposalId(),
                clientOrderId, exchangeLabel, proposal.getSymbol(), "LONG", "OPEN", leg.qty(),
                isLimit ? "LIMIT" : "MARKET", System.currentTimeMillis()));

        OrderEntity order = new OrderEntity(clientOrderId, commandId, accountId, exchangeLabel, proposal.getSymbol(),
                "LONG", "OPEN", leg.qty(), System.currentTimeMillis());
        order = orderRepository.save(order);

        AdapterOrderRequest request = new AdapterOrderRequest(proposal.getSymbol(), AdapterOrderSide.BUY,
                isLimit ? AdapterOrderType.LIMIT : AdapterOrderType.MARKET, leg.qty(), limitPrice, clientOrderId,
                OrderIntent.ENTRY, null, isAddOn ? null : new TpSlAttachment(tp, sl), proposal.getLeverage());

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

        // A LIMIT order still resting after the short poll window is normal (it's away from
        // market on purpose) — leave it live on the exchange, don't cancel it and don't report it
        // as a failure. Nothing gets recorded as a position until it actually fills; the
        // reconciliation poller (stage 4) is what picks that up, same as any other exchange-side
        // fill this system didn't directly witness.
        if (isLimit && result.state() != AdapterOrderState.FILLED
                && result.state() != AdapterOrderState.REJECTED
                && result.state() != AdapterOrderState.CANCELED
                && result.state() != AdapterOrderState.FAILED) {
            order.applyResult(result.exchangeOrderId(), result.state().name(), null, null, null, null, null,
                    System.currentTimeMillis());
            orderRepository.save(order);
            markInboxDone(idempotencyKey, accountId);
            return ExecutionLegResult.limitWorking(accountId);
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

        PositionEntity position = existingPosition != null ? existingPosition
                : PositionEntity.newFlat(accountId, exchangeLabel, proposal.getSymbol());
        BigDecimal finalQty = filledQty;
        BigDecimal finalEntry = avgPrice;
        if (isAddOn) {
            // Weighted-average entry across the old and the new fill — the position keeps growing
            // under one row, never a second one, same as an exchange would report it.
            BigDecimal oldQty = existingPosition.getQuantity();
            finalQty = oldQty.add(filledQty);
            finalEntry = oldQty.multiply(existingPosition.getEntryPrice()).add(filledQty.multiply(avgPrice))
                    .divide(finalQty, 8, java.math.RoundingMode.HALF_UP);
        }
        position.setSide("LONG");
        position.setQuantity(finalQty);
        position.setEntryPrice(finalEntry);
        position.setStatus("OPEN");
        position.setUpdatedAt(System.currentTimeMillis());

        boolean stopVerified;
        if (isAddOn && "bingx".equalsIgnoreCase(account.exchange())) {
            // Bybit's stop is position-level and already covers finalQty untouched (no attachment
            // was sent on this leg — see the request-building comment above). BingX's stop is a
            // separate reduce-only order still sized to the OLD qty; resize it to the new total by
            // cancelling the old SL/TP pair and placing one fresh pair for finalQty.
            try {
                adapter.cancelReduceOnlyOrder(proposal.getSymbol(), true);
                adapter.cancelReduceOnlyOrder(proposal.getSymbol(), false);
                adapter.placeReduceOnlyStop(proposal.getSymbol(), finalQty, sl);
                adapter.placeReduceOnlyTakeProfit(proposal.getSymbol(), finalQty, tp);
                position.setSlPrice(sl.toPlainString());
                position.setTpPrice(tp.toPlainString());
                stopVerified = stopGuard.hasStopOrder(account.exchange(), proposal.getSymbol());
            } catch (RuntimeException e) {
                log.error("[{}] add-on stop resize failed for {}: {}", accountId, proposal.getSymbol(), e.toString());
                stopVerified = false;
            }
        } else if (isAddOn) {
            // Bybit add-on: exchange-side stop is untouched, so the DB keeps whatever sl/tp is
            // actually still active on the position rather than claiming the new ticket's values.
            stopVerified = stopGuard.hasStopOrder(account.exchange(), proposal.getSymbol());
        } else {
            position.setOpenedAt(System.currentTimeMillis());
            position.setSlPrice(sl.toPlainString());
            position.setTpPrice(tp.toPlainString());
            // 4.6 step 5 / 3rd invariant: the venue attaches TP/SL atomically at entry, but we
            // verify it actually landed rather than trusting the attach silently succeeded.
            stopVerified = stopGuard.verifyAndRepair(account.exchange(), proposal.getSymbol(), finalQty, sl);
        }

        // plannedRiskAmount feeds openPortfolioRisk (ProposalPreviewService sums it across open
        // positions) — on an add-on this MUST be recomputed from the combined qty and whichever
        // sl actually ended up active (position.getSlPrice(), set above per-venue), never taken
        // from leg.riskUsd() alone: that's only the risk of the increment, and overwriting the
        // position's total risk with just the increment's risk would understate the real portfolio
        // risk and let a later entry pass a gate it shouldn't.
        if (isAddOn) {
            BigDecimal effectiveSl = position.getSlPrice() != null ? new BigDecimal(position.getSlPrice()) : null;
            BigDecimal riskAmount = effectiveSl != null ? finalQty.multiply(finalEntry.subtract(effectiveSl).abs()) : null;
            position.setPlannedRiskAmount(riskAmount != null ? riskAmount.toPlainString() : null);
        } else {
            position.setPlannedRiskAmount(leg.riskUsd() != null ? leg.riskUsd().toPlainString() : null);
        }
        positionRepository.save(position);

        markInboxDone(idempotencyKey, accountId);
        return ExecutionLegResult.filled(accountId, filledQty, avgPrice, sl, tp, leg.riskUsd(), stopVerified);
    }

    /**
     * Null (approved) or a rejection reason. Mirrors the caps {@link com.sonar.trading.sizing.RiskBasedPositionSizer}
     * already enforces on a single order, but against the position's COMBINED size — {@code sizeManual}
     * has no notion of "there's already $900 open on this symbol", so ten $1000 add-ons under a
     * $1000-per-order cap would otherwise sum to $10 000 with nothing in the pipeline noticing.
     */
    private String checkAddOnCaps(String accountId, PositionEntity existingPosition, BigDecimal legQty, BigDecimal entry) {
        AccountRiskEntity riskEntity = accountRiskRepository.findById(accountId).orElse(null);
        if (riskEntity == null) {
            return null; // no profile row yet — same as previewOne, shouldn't happen post-preview
        }
        AccountRiskProfile profile = AccountRiskProfile.from(riskEntity);
        BigDecimal existingNotional = existingPosition.getQuantity().multiply(existingPosition.getEntryPrice());
        BigDecimal combinedNotional = existingNotional.add(legQty.multiply(entry));

        if (combinedNotional.compareTo(profile.maxPositionNotional()) > 0) {
            return "доливка превысит лимит аккаунта по нотионалу: было $" + existingNotional.setScale(2, java.math.RoundingMode.HALF_UP)
                    + " + новое $" + legQty.multiply(entry).setScale(2, java.math.RoundingMode.HALF_UP)
                    + " = $" + combinedNotional.setScale(2, java.math.RoundingMode.HALF_UP)
                    + " > лимит $" + profile.maxPositionNotional().setScale(2, java.math.RoundingMode.HALF_UP);
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        DailyBaselineEntity baseline = dailyBaselineRepository.findByIdAccountIdAndIdTradingDay(accountId, today)
                .orElse(null);
        if (baseline != null) {
            BigDecimal equity = new BigDecimal(baseline.getEquityAtOpen());
            if (equity.signum() > 0) {
                BigDecimal maxLeverageNotional = equity.multiply(BigDecimal.valueOf(profile.maxLeverage()));
                if (combinedNotional.compareTo(maxLeverageNotional) > 0) {
                    return "доливка превысит лимит аккаунта по плечу: итоговый нотионал $"
                            + combinedNotional.setScale(2, java.math.RoundingMode.HALF_UP)
                            + " > equity×maxLeverage $" + maxLeverageNotional.setScale(2, java.math.RoundingMode.HALF_UP);
                }
            }
        }
        return null;
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
