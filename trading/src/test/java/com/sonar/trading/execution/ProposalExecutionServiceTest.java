package com.sonar.trading.execution;

import com.sonar.trading.config.AccountProps;
import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.entity.AccountRiskEntity;
import com.sonar.trading.domain.entity.DailyBaselineEntity;
import com.sonar.trading.domain.entity.OrderCommandEntity;
import com.sonar.trading.domain.entity.OrderEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.entity.ProposalEntity;
import com.sonar.trading.domain.repository.AccountRiskRepository;
import com.sonar.trading.domain.repository.DailyBaselineRepository;
import com.sonar.trading.domain.repository.FillRepository;
import com.sonar.trading.domain.repository.OrderCommandRepository;
import com.sonar.trading.domain.repository.OrderRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.domain.repository.ProposalRepository;
import com.sonar.trading.domain.repository.SignalInboxRepository;
import com.sonar.trading.exchange.dto.AdapterOrderResult;
import com.sonar.trading.exchange.model.AdapterOrderState;
import com.sonar.trading.exchange.spi.ExecutionAdapter;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import com.sonar.trading.risk.AccountLegPreview;
import com.sonar.trading.risk.ProposalPreviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * PR #2 follow-up review: none of this existed a test for — {@code checkNotionalCaps}, the add-on
 * risk recompute, and the LIMIT-still-resting outcome. Covers exactly those, not a full
 * re-verification of {@link ProposalExecutionService#confirm} end to end (that needs a live
 * adapter/DB either way, same as the rest of this class's exchange-touching methods).
 */
class ProposalExecutionServiceTest {

    private static final String ACCOUNT_ID = "acct-1";

    private ProposalRepository proposals;
    private AccountRegistry accountRegistry;
    private ProposalPreviewService previewService;
    private PositionRepository positionRepository;
    private OrderCommandRepository orderCommandRepository;
    private OrderRepository orderRepository;
    private FillRepository fillRepository;
    private SignalInboxRepository signalInboxRepository;
    private ExecutionAdapterRegistry adapters;
    private StopGuard stopGuard;
    private ProposalClaimer claimer;
    private AccountRiskRepository accountRiskRepository;
    private DailyBaselineRepository dailyBaselineRepository;
    private ExecutionAdapter adapter;

    private ProposalExecutionService service;

    @BeforeEach
    void setUp() {
        proposals = Mockito.mock(ProposalRepository.class);
        accountRegistry = Mockito.mock(AccountRegistry.class);
        previewService = Mockito.mock(ProposalPreviewService.class);
        positionRepository = Mockito.mock(PositionRepository.class);
        orderCommandRepository = Mockito.mock(OrderCommandRepository.class);
        orderRepository = Mockito.mock(OrderRepository.class);
        fillRepository = Mockito.mock(FillRepository.class);
        signalInboxRepository = Mockito.mock(SignalInboxRepository.class);
        adapters = Mockito.mock(ExecutionAdapterRegistry.class);
        stopGuard = Mockito.mock(StopGuard.class);
        claimer = Mockito.mock(ProposalClaimer.class);
        accountRiskRepository = Mockito.mock(AccountRiskRepository.class);
        dailyBaselineRepository = Mockito.mock(DailyBaselineRepository.class);
        adapter = Mockito.mock(ExecutionAdapter.class);

        service = new ProposalExecutionService(proposals, accountRegistry, previewService, positionRepository,
                orderCommandRepository, orderRepository, fillRepository, signalInboxRepository, adapters,
                stopGuard, claimer, accountRiskRepository, dailyBaselineRepository);

        AccountProps account = new AccountProps(ACCOUNT_ID, "bingx", "key-bingx", "secret", "https://open-api-vst.bingx.com");
        when(accountRegistry.byId(ACCOUNT_ID)).thenReturn(Optional.of(account));
        // checkPriceDrift's account scan (confirm()'s pre-preview sanity gate) — same account list.
        when(accountRegistry.all()).thenReturn(List.of(account));
        when(adapters.forAccount(ACCOUNT_ID)).thenReturn(Optional.of(adapter));
        when(adapter.getMarkPrice(anyString())).thenReturn(new BigDecimal("100"));
        when(claimer.claim(anyString())).thenReturn(true);
        when(signalInboxRepository.findByIdempotencyKeyAndAccountId(anyString(), anyString())).thenReturn(Optional.empty());
        when(orderRepository.findAllActiveByAccountId(ACCOUNT_ID)).thenReturn(List.of());
        // OrderEntity order = orderRepository.save(order) reassigns to the mock's return value —
        // must echo the argument back, same as a real save() would.
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountRiskEntity risk = AccountRiskEntity.defaultsFor(ACCOUNT_ID);
        risk.setMaxPositionNotional("1000");
        risk.setMaxLeverage(50); // high enough that only the notional cap is under test
        when(accountRiskRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(risk));

        DailyBaselineEntity baseline = DailyBaselineEntity.open(ACCOUNT_ID, LocalDate.now(ZoneOffset.UTC), "100000", "100000");
        when(dailyBaselineRepository.findByIdAccountIdAndIdTradingDay(anyString(), any())).thenReturn(Optional.of(baseline));
    }

    private ProposalEntity proposal(String entry, String sl, String tp, String notional, String orderType, String limitPrice) {
        return ProposalEntity.pending(UUID.randomUUID().toString(), "BTCUSDT", "LONG",
                entry, sl, tp, "test", System.currentTimeMillis(), ACCOUNT_ID, notional, 10,
                orderType, limitPrice);
    }

    private void stubProposal(ProposalEntity p) {
        when(proposals.findById(p.getProposalId())).thenReturn(Optional.of(p));
    }

    @Test
    void rejectsAddOnThatWouldExceedNotionalCap() {
        PositionEntity existing = PositionEntity.newFlat(ACCOUNT_ID, "BINGX", "BTCUSDT");
        existing.setStatus("OPEN");
        existing.setSide("LONG");
        existing.setQuantity(new BigDecimal("8")); // 8 * 100 = 800 already open
        existing.setEntryPrice(new BigDecimal("100"));
        when(positionRepository.findByAccountIdAndSymbol(ACCOUNT_ID, "BTCUSDT")).thenReturn(Optional.of(existing));

        ProposalEntity p = proposal("100", "90", "110", "300", "MARKET", null); // 800 + 300 > 1000 cap
        stubProposal(p);
        when(previewService.preview(anyString(), any(), any(), anyString(), any()))
                .thenReturn(List.of(AccountLegPreview.approved(ACCOUNT_ID, new BigDecimal("3"), new BigDecimal("30"))));

        ProposalExecutionService.ConfirmOutcome outcome = service.confirm(p.getProposalId());

        assertThat(outcome).isInstanceOf(ProposalExecutionService.ConfirmOutcome.Executed.class);
        ExecutionLegResult leg = ((ProposalExecutionService.ConfirmOutcome.Executed) outcome).legs().get(0);
        assertThat(leg.success()).isFalse();
        assertThat(leg.errorCode()).isEqualTo("NOTIONAL_CAP_EXCEEDED");
        assertThat(leg.errorParams()).containsKey("limit");
        assertThat(leg.errorReason()).contains("notional");
        Mockito.verify(adapter, Mockito.never()).placeOrder(any());
    }

    @Test
    void rejectsFreshEntryWhenPendingLimitOrdersAlreadyReserveTheCap() {
        // No open position yet, but a resting LIMIT order already committed 700 of the 1000 cap —
        // this is exactly the gap the follow-up review flagged: a pending order reserved nothing
        // before this fix, so this leg would previously have passed unopposed.
        when(positionRepository.findByAccountIdAndSymbol(ACCOUNT_ID, "BTCUSDT")).thenReturn(Optional.empty());

        OrderEntity pendingOrder = new OrderEntity("cid-pending", "cmd-pending", ACCOUNT_ID, "BINGX", "BTCUSDT",
                "LONG", "OPEN", new BigDecimal("7"), System.currentTimeMillis());
        when(orderRepository.findAllActiveByAccountId(ACCOUNT_ID)).thenReturn(List.of(pendingOrder));
        OrderCommandEntity pendingCommand = new OrderCommandEntity("cmd-pending", ACCOUNT_ID, "other-proposal",
                "cid-pending", "BINGX", "BTCUSDT", "LONG", "OPEN", new BigDecimal("7"), "LIMIT",
                new BigDecimal("100"), System.currentTimeMillis());
        when(orderCommandRepository.findByCommandId("cmd-pending")).thenReturn(Optional.of(pendingCommand));

        ProposalEntity p = proposal("100", "90", "110", "400", "MARKET", null); // 700 + 400 > 1000 cap
        stubProposal(p);
        when(previewService.preview(anyString(), any(), any(), anyString(), any()))
                .thenReturn(List.of(AccountLegPreview.approved(ACCOUNT_ID, new BigDecimal("4"), new BigDecimal("40"))));

        ProposalExecutionService.ConfirmOutcome outcome = service.confirm(p.getProposalId());

        ExecutionLegResult leg = ((ProposalExecutionService.ConfirmOutcome.Executed) outcome).legs().get(0);
        assertThat(leg.success()).isFalse();
        assertThat(leg.errorCode()).isEqualTo("NOTIONAL_CAP_EXCEEDED");
        assertThat(leg.errorReason()).contains("reserved (pending limit)");
        Mockito.verify(adapter, Mockito.never()).placeOrder(any());
    }

    @Test
    void addOnRecomputesPlannedRiskFromCombinedQtyNotJustTheIncrement() {
        PositionEntity existing = PositionEntity.newFlat(ACCOUNT_ID, "BYBIT", "BTCUSDT");
        existing.setStatus("OPEN");
        existing.setSide("LONG");
        existing.setQuantity(new BigDecimal("5"));
        existing.setEntryPrice(new BigDecimal("100"));
        existing.setSlPrice("90");
        when(positionRepository.findByAccountIdAndSymbol(ACCOUNT_ID, "BTCUSDT")).thenReturn(Optional.of(existing));

        AccountProps bybitAccount = new AccountProps(ACCOUNT_ID, "bybit", "key-bybit", "secret", "https://api-demo.bybit.com");
        when(accountRegistry.byId(ACCOUNT_ID)).thenReturn(Optional.of(bybitAccount));
        when(accountRegistry.all()).thenReturn(List.of(bybitAccount));
        when(adapters.forAccount(ACCOUNT_ID)).thenReturn(Optional.of(adapter));

        ProposalEntity p = proposal("100", "90", "110", "100", "MARKET", null); // small add-on, well under cap
        stubProposal(p);
        when(previewService.preview(anyString(), any(), any(), anyString(), any()))
                .thenReturn(List.of(AccountLegPreview.approved(ACCOUNT_ID, new BigDecimal("1"), new BigDecimal("10"))));
        when(adapter.placeOrder(any())).thenReturn(new AdapterOrderResult(
                "ex-1", "cid-1", "BTCUSDT", AdapterOrderState.FILLED,
                new BigDecimal("1"), new BigDecimal("100"), BigDecimal.ZERO, System.currentTimeMillis()));
        when(stopGuard.hasStopOrder(ACCOUNT_ID, "BTCUSDT")).thenReturn(true);

        service.confirm(p.getProposalId());

        var captor = org.mockito.ArgumentCaptor.forClass(PositionEntity.class);
        Mockito.verify(positionRepository).save(captor.capture());
        PositionEntity saved = captor.getValue();
        // qty 5->6, entry stays 100 (both legs at 100), sl stays 90 (Bybit add-on doesn't move it)
        // -> planned risk = 6 * |100-90| = 60, NOT leg.riskUsd()=10 (the increment's own risk).
        assertThat(saved.getQuantity()).isEqualByComparingTo("6");
        assertThat(saved.getPlannedRiskAmount()).isNotNull();
        assertThat(new BigDecimal(saved.getPlannedRiskAmount())).isEqualByComparingTo("60");
    }

    @Test
    void limitEntryStillRestingAfterPollIsReportedAsWorkingNotFailed() {
        when(positionRepository.findByAccountIdAndSymbol(ACCOUNT_ID, "BTCUSDT")).thenReturn(Optional.empty());

        ProposalEntity p = proposal("100", "90", "110", "100", "LIMIT", "100");
        stubProposal(p);
        when(previewService.preview(anyString(), any(), any(), anyString(), any()))
                .thenReturn(List.of(AccountLegPreview.approved(ACCOUNT_ID, new BigDecimal("1"), new BigDecimal("10"))));
        AdapterOrderResult resting = new AdapterOrderResult(
                "ex-1", "cid-1", "BTCUSDT", AdapterOrderState.NEW, null, null, null, System.currentTimeMillis());
        when(adapter.placeOrder(any())).thenReturn(resting);
        when(adapter.getOrderStatus(anyString(), anyString())).thenReturn(resting);

        ProposalExecutionService.ConfirmOutcome outcome = service.confirm(p.getProposalId());

        ExecutionLegResult leg = ((ProposalExecutionService.ConfirmOutcome.Executed) outcome).legs().get(0);
        assertThat(leg.success()).isTrue();
        assertThat(leg.filledQty()).isNull();
        Mockito.verify(positionRepository, Mockito.never()).save(any());
    }
}
