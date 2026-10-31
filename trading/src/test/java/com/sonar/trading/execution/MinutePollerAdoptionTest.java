package com.sonar.trading.execution;

import com.sonar.trading.config.AccountProps;
import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.entity.AccountRiskEntity;
import com.sonar.trading.domain.entity.NotificationEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.repository.AccountRiskRepository;
import com.sonar.trading.domain.repository.NotificationRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.domain.repository.StrategySettingsRepository;
import com.sonar.trading.reconciliation.AccountReader;
import com.sonar.trading.reconciliation.AccountReaderRegistry;
import com.sonar.trading.reconciliation.model.ExchangeBalance;
import com.sonar.trading.reconciliation.model.ExchangePosition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The exchange is the source of truth for "is a position open". A position opened directly on the
 * venue — manual panel bypassed, another tool, or an account that already had positions when it was
 * registered — must land in the DB like any other, or it stays invisible in the panel and its close
 * never reaches Telegram.
 */
class MinutePollerAdoptionTest {

    private static final String ACCOUNT = "bingx-prod";

    private AccountRegistry accountRegistry;
    private AccountReaderRegistry accountReaders;
    private AccountRiskRepository riskRepository;
    private PositionRepository positionRepository;
    private NotificationRepository notificationRepository;
    private StopGuard stopGuard;
    private PollerTransactions transactions;
    private AccountReader reader;
    private MinutePoller poller;

    @BeforeEach
    void setUp() {
        accountRegistry = Mockito.mock(AccountRegistry.class);
        accountReaders = Mockito.mock(AccountReaderRegistry.class);
        riskRepository = Mockito.mock(AccountRiskRepository.class);
        positionRepository = Mockito.mock(PositionRepository.class);
        notificationRepository = Mockito.mock(NotificationRepository.class);
        stopGuard = Mockito.mock(StopGuard.class);
        transactions = Mockito.mock(PollerTransactions.class);
        reader = Mockito.mock(AccountReader.class);

        when(accountRegistry.all()).thenReturn(List.of(
                new AccountProps(ACCOUNT, "bingx", "key", "secret", "https://open-api.bingx.com")));
        when(accountReaders.forAccount(ACCOUNT)).thenReturn(Optional.of(reader));
        when(reader.getBalance()).thenReturn(Optional.empty()); // balance path is not under test
        when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stopGuard.isSupported(ACCOUNT)).thenReturn(false); // stop checks covered by StopGuardTest

        poller = new MinutePoller(accountRegistry, accountReaders, riskRepository, positionRepository,
                Mockito.mock(StrategySettingsRepository.class), notificationRepository, stopGuard, transactions);
    }

    private void givenRisk(boolean active) {
        AccountRiskEntity risk = AccountRiskEntity.defaultsFor(ACCOUNT);
        risk.setActive(active);
        when(riskRepository.findById(ACCOUNT)).thenReturn(Optional.of(risk));
    }

    private static ExchangePosition live(String symbol, String qty) {
        return new ExchangePosition(symbol, "LONG", new BigDecimal(qty), new BigDecimal("79264.1"),
                new BigDecimal("79300"), new BigDecimal("4.87"), 70, new BigDecimal("37472.3"));
    }

    @Test
    void adoptsALivePositionThatHasNoDbRow() {
        givenRisk(true);
        when(reader.getPositions()).thenReturn(List.of(live("BTCUSDT", "0.012")));
        when(positionRepository.findAllOpenByAccountId(ACCOUNT)).thenReturn(new java.util.ArrayList<>());
        when(positionRepository.findByAccountIdAndSymbol(ACCOUNT, "BTCUSDT")).thenReturn(Optional.empty());

        poller.tick();

        ArgumentCaptor<PositionEntity> saved = ArgumentCaptor.forClass(PositionEntity.class);
        verify(positionRepository).save(saved.capture());
        PositionEntity adopted = saved.getValue();
        assertThat(adopted.getAccountId()).isEqualTo(ACCOUNT);
        assertThat(adopted.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(adopted.getStatus()).isEqualTo("OPEN");
        assertThat(adopted.getQuantity()).isEqualByComparingTo("0.012");
        assertThat(adopted.getOpenedVia()).isEqualTo("EXCHANGE");
        // Nobody told us these — recording a guess would be worse than an honest blank.
        assertThat(adopted.getSlPrice()).isNull();
        assertThat(adopted.getTpPrice()).isNull();
    }

    @Test
    void announcesAnAdoptionOnce() {
        givenRisk(true);
        when(reader.getPositions()).thenReturn(List.of(live("BTCUSDT", "0.012")));
        when(positionRepository.findAllOpenByAccountId(ACCOUNT)).thenReturn(new java.util.ArrayList<>());
        when(positionRepository.findByAccountIdAndSymbol(ACCOUNT, "BTCUSDT")).thenReturn(Optional.empty());

        poller.tick();

        ArgumentCaptor<NotificationEntity> note = ArgumentCaptor.forClass(NotificationEntity.class);
        verify(notificationRepository).save(note.capture());
        assertThat(note.getValue().getKind()).isEqualTo(NotificationEntity.POSITION_ADOPTED);
        assertThat(note.getValue().getCode()).isEqualTo("POSITION_ADOPTED");
        assertThat(note.getValue().getMessage()).doesNotContainPattern("[\\u0400-\\u04FF]");
    }

    @Test
    void doesNotAdoptAPositionAlreadyTracked() {
        givenRisk(true);
        PositionEntity existing = PositionEntity.newFlat(ACCOUNT, "BINGX", "BTCUSDT");
        existing.setStatus("OPEN");
        existing.setQuantity(new BigDecimal("0.012"));
        when(reader.getPositions()).thenReturn(List.of(live("BTCUSDT", "0.012")));
        when(positionRepository.findAllOpenByAccountId(ACCOUNT))
                .thenReturn(new java.util.ArrayList<>(List.of(existing)));

        poller.tick();

        verify(positionRepository, never()).save(any());
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void ignoresFlatRows() {
        givenRisk(true);
        ExchangePosition flat = new ExchangePosition("BTCUSDT", "FLAT", BigDecimal.ZERO,
                null, null, null, null, null);
        when(reader.getPositions()).thenReturn(List.of(flat));
        when(positionRepository.findAllOpenByAccountId(ACCOUNT)).thenReturn(new java.util.ArrayList<>());

        poller.tick();

        verify(positionRepository, never()).save(any());
    }

    @Test
    void reusesTheLifetimeRowWhenTheSymbolWasTradedBefore() {
        // positionId is unique per accountId:symbol, so a previously closed row must be reopened
        // rather than inserted a second time.
        givenRisk(true);
        PositionEntity closed = PositionEntity.newFlat(ACCOUNT, "BINGX", "BTCUSDT");
        when(reader.getPositions()).thenReturn(List.of(live("BTCUSDT", "0.012")));
        when(positionRepository.findAllOpenByAccountId(ACCOUNT)).thenReturn(new java.util.ArrayList<>());
        when(positionRepository.findByAccountIdAndSymbol(ACCOUNT, "BTCUSDT")).thenReturn(Optional.of(closed));

        poller.tick();

        ArgumentCaptor<PositionEntity> saved = ArgumentCaptor.forClass(PositionEntity.class);
        verify(positionRepository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(closed);
        assertThat(saved.getValue().getStatus()).isEqualTo("OPEN");
    }

    @Test
    void clearsStaleFieldsFromThePreviousTradeOnTheReusedRow() {
        // PR review fix: the row findByAccountIdAndSymbol hands back can carry SL/TP/planned-risk
        // from a DIFFERENT, earlier trade on this symbol — a fresh newFlat() row (as the test above
        // uses) never exposes this, because it's already blank. Without clearing them,
        // verifyAndRepair would fall back to a stale SL price from a past position and place a
        // reduce-only stop at that stale level on a real account.
        givenRisk(true);
        PositionEntity staleRow = PositionEntity.newFlat(ACCOUNT, "BINGX", "BTCUSDT");
        staleRow.setSlPrice("999999");
        staleRow.setTpPrice("1");
        staleRow.setPlannedRiskAmount("500");
        staleRow.setQtyMismatch(true);
        when(reader.getPositions()).thenReturn(List.of(live("BTCUSDT", "0.012")));
        when(positionRepository.findAllOpenByAccountId(ACCOUNT)).thenReturn(new java.util.ArrayList<>());
        when(positionRepository.findByAccountIdAndSymbol(ACCOUNT, "BTCUSDT")).thenReturn(Optional.of(staleRow));

        poller.tick();

        ArgumentCaptor<PositionEntity> saved = ArgumentCaptor.forClass(PositionEntity.class);
        verify(positionRepository).save(saved.capture());
        assertThat(saved.getValue().getSlPrice()).isNull();
        assertThat(saved.getValue().getTpPrice()).isNull();
        assertThat(saved.getValue().getPlannedRiskAmount()).isNull();
        assertThat(saved.getValue().isQtyMismatch()).isFalse();
    }

    @Test
    void skipsAnInactiveAccountEntirely() {
        givenRisk(false);

        poller.tick();

        verify(reader, never()).getPositions();
        verify(positionRepository, never()).save(any());
    }

    @Test
    void createsADefaultRiskProfileForAFreshAccountInsteadOfSkippingIt() {
        // A newly registered account has no risk row yet; that used to mean "never polled" until a
        // human opened its risk profile in the panel, so a new account silently got no monitoring.
        when(riskRepository.findById(ACCOUNT)).thenReturn(Optional.empty());
        when(riskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reader.getPositions()).thenReturn(List.of());
        when(positionRepository.findAllOpenByAccountId(ACCOUNT)).thenReturn(new java.util.ArrayList<>());

        poller.tick();

        ArgumentCaptor<AccountRiskEntity> created = ArgumentCaptor.forClass(AccountRiskEntity.class);
        verify(riskRepository).save(created.capture());
        assertThat(created.getValue().getAccountId()).isEqualTo(ACCOUNT);
        assertThat(created.getValue().isActive()).isTrue();
        assertThat(created.getValue().isTradable()).isFalse();
        verify(reader).getPositions(); // and the account is polled on that same tick
    }
}
