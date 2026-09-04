package com.sonar.trading.execution;

import com.sonar.trading.config.AccountProps;
import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.entity.FillEntity;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** PR #2 follow-up review: a LIMIT order that partially fills and THEN reaches a terminal state
 *  (canceled from the orders tab, or timed out by the venue after a partial fill) must still fold
 *  the filled part into a position — the first version discarded it whenever state wasn't exactly
 *  FILLED. */
class PendingLimitOrderReconcilerTest {

    private static final String ACCOUNT_ID = "acct-1";
    private static final AccountProps ACCOUNT = new AccountProps(ACCOUNT_ID, "bybit");

    private AccountRegistry accountRegistry;
    private ExecutionAdapterRegistry adapters;
    private OrderRepository orderRepository;
    private OrderCommandRepository orderCommandRepository;
    private ProposalRepository proposalRepository;
    private PositionRepository positionRepository;
    private NotificationRepository notificationRepository;
    private StopGuard stopGuard;
    private FillRepository fillRepository;
    private ExecutionAdapter adapter;

    private PendingLimitOrderReconciler reconciler;

    @BeforeEach
    void setUp() {
        accountRegistry = Mockito.mock(AccountRegistry.class);
        adapters = Mockito.mock(ExecutionAdapterRegistry.class);
        orderRepository = Mockito.mock(OrderRepository.class);
        orderCommandRepository = Mockito.mock(OrderCommandRepository.class);
        proposalRepository = Mockito.mock(ProposalRepository.class);
        positionRepository = Mockito.mock(PositionRepository.class);
        notificationRepository = Mockito.mock(NotificationRepository.class);
        stopGuard = Mockito.mock(StopGuard.class);
        fillRepository = Mockito.mock(FillRepository.class);
        adapter = Mockito.mock(ExecutionAdapter.class);

        reconciler = new PendingLimitOrderReconciler(accountRegistry, adapters, orderRepository,
                orderCommandRepository, proposalRepository, positionRepository, notificationRepository,
                stopGuard, fillRepository);

        when(accountRegistry.all()).thenReturn(List.of(ACCOUNT));
        when(adapters.forExchange("bybit")).thenReturn(Optional.of(adapter));
        when(positionRepository.findByAccountIdAndSymbol(ACCOUNT_ID, "BTCUSDT")).thenReturn(Optional.empty());
        when(orderCommandRepository.findByCommandId(anyString())).thenReturn(Optional.empty());
    }

    private OrderEntity activeOrder() {
        return new OrderEntity("cid-1", "cmd-1", ACCOUNT_ID, "BYBIT", "BTCUSDT",
                "LONG", "OPEN", new BigDecimal("10"), System.currentTimeMillis());
    }

    @Test
    void foldsPartialFillEvenWhenTheOrderEndsCanceled() {
        OrderEntity order = activeOrder();
        when(orderRepository.findAllActiveByAccountId(ACCOUNT_ID)).thenReturn(List.of(order));
        // Bybit's PartiallyFilledCanceled maps to plain CANCELED (BybitOrderMapper) — the order
        // asked for 10, only 4 filled before the operator canceled the rest.
        when(adapter.getOrderStatus("BTCUSDT", "cid-1")).thenReturn(new AdapterOrderResult(
                "ex-1", "cid-1", "BTCUSDT", AdapterOrderState.CANCELED,
                new BigDecimal("4"), new BigDecimal("100"), BigDecimal.ZERO, System.currentTimeMillis()));

        reconciler.tick();

        ArgumentCaptor<PositionEntity> posCaptor = ArgumentCaptor.forClass(PositionEntity.class);
        Mockito.verify(positionRepository).save(posCaptor.capture());
        assertThat(posCaptor.getValue().getQuantity()).isEqualByComparingTo("4");
        assertThat(posCaptor.getValue().getStatus()).isEqualTo("OPEN");

        ArgumentCaptor<FillEntity> fillCaptor = ArgumentCaptor.forClass(FillEntity.class);
        Mockito.verify(fillRepository).save(fillCaptor.capture());
        assertThat(fillCaptor.getValue().getQuantity()).isEqualByComparingTo("4");
    }

    @Test
    void fullyFilledOrderFoldsIntoPositionWithRiskFromProposal() {
        OrderEntity order = activeOrder();
        when(orderRepository.findAllActiveByAccountId(ACCOUNT_ID)).thenReturn(List.of(order));
        when(adapter.getOrderStatus("BTCUSDT", "cid-1")).thenReturn(new AdapterOrderResult(
                "ex-1", "cid-1", "BTCUSDT", AdapterOrderState.FILLED,
                new BigDecimal("10"), new BigDecimal("100"), BigDecimal.ZERO, System.currentTimeMillis()));
        OrderCommandEntity command = new OrderCommandEntity("cmd-1", ACCOUNT_ID, "prop-1", "cid-1",
                "BYBIT", "BTCUSDT", "LONG", "OPEN", new BigDecimal("10"), "LIMIT",
                new BigDecimal("100"), System.currentTimeMillis());
        when(orderCommandRepository.findByCommandId("cmd-1")).thenReturn(Optional.of(command));
        ProposalEntity proposal = ProposalEntity.pending("prop-1", "BTCUSDT", "LONG",
                "100", "90", "110", "test", System.currentTimeMillis(), ACCOUNT_ID, "1000", 10, "LIMIT", "100");
        when(proposalRepository.findById("prop-1")).thenReturn(Optional.of(proposal));
        when(stopGuard.hasStopOrder("bybit", "BTCUSDT")).thenReturn(true);

        reconciler.tick();

        ArgumentCaptor<PositionEntity> posCaptor = ArgumentCaptor.forClass(PositionEntity.class);
        Mockito.verify(positionRepository).save(posCaptor.capture());
        PositionEntity saved = posCaptor.getValue();
        assertThat(saved.getQuantity()).isEqualByComparingTo("10");
        assertThat(saved.getEntryPrice()).isEqualByComparingTo("100");
        assertThat(new BigDecimal(saved.getPlannedRiskAmount())).isEqualByComparingTo("100"); // 10 * |100-90|
        Mockito.verify(notificationRepository, Mockito.never()).save(any());
    }

    @Test
    void rejectedWithNothingFilledCreatesNoPosition() {
        OrderEntity order = activeOrder();
        when(orderRepository.findAllActiveByAccountId(ACCOUNT_ID)).thenReturn(List.of(order));
        when(adapter.getOrderStatus("BTCUSDT", "cid-1")).thenReturn(new AdapterOrderResult(
                "ex-1", "cid-1", "BTCUSDT", AdapterOrderState.REJECTED,
                null, null, null, System.currentTimeMillis()));

        reconciler.tick();

        Mockito.verify(positionRepository, Mockito.never()).save(any());
        Mockito.verify(fillRepository, Mockito.never()).save(any());
    }

    @Test
    void stillRestingOrderIsLeftAlone() {
        OrderEntity order = activeOrder();
        when(orderRepository.findAllActiveByAccountId(ACCOUNT_ID)).thenReturn(List.of(order));
        when(adapter.getOrderStatus("BTCUSDT", "cid-1")).thenReturn(new AdapterOrderResult(
                "ex-1", "cid-1", "BTCUSDT", AdapterOrderState.NEW,
                null, null, null, System.currentTimeMillis()));

        reconciler.tick();

        Mockito.verify(orderRepository, Mockito.never()).save(any());
        Mockito.verify(positionRepository, Mockito.never()).save(any());
    }
}
