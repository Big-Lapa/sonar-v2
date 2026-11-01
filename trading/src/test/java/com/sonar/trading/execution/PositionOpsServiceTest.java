package com.sonar.trading.execution;

import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.repository.NotificationRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.execution.adapter.ExecutionAdapterRegistry;
import com.sonar.trading.reconciliation.AccountReaderRegistry;
import com.sonar.trading.sizing.QuantityNormalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** Operator-action failures carry a stable code (translated by the admin panel / Telegram bot) and an English fallback. */
class PositionOpsServiceTest {

    private PositionRepository positionRepository;
    private PositionOpsService service;

    @BeforeEach
    void setUp() {
        positionRepository = Mockito.mock(PositionRepository.class);
        service = new PositionOpsService(positionRepository, Mockito.mock(AccountRegistry.class),
                Mockito.mock(ExecutionAdapterRegistry.class), Mockito.mock(NotificationRepository.class),
                Mockito.mock(QuantityNormalizer.class), Mockito.mock(AccountReaderRegistry.class));
    }

    @Test
    void closeAllWithNoOpenPositionsFailsWithCodeAndEnglishReason() {
        when(positionRepository.findAllOpenByAccountId("acc1")).thenReturn(List.of());

        PositionOpsService.OpResult result = service.closeAll("acc1", null);

        assertThat(result).isInstanceOf(PositionOpsService.OpResult.Failed.class);
        PositionOpsService.OpResult.Failed failed = (PositionOpsService.OpResult.Failed) result;
        assertThat(failed.code()).isEqualTo("NO_OPEN_POSITIONS");
        assertThat(failed.reason()).isEqualTo("no open positions");
    }

    @Test
    void closeAllForSymbolPutsTheSymbolInParamsAndReason() {
        when(positionRepository.findByAccountIdAndSymbol("acc1", "BTCUSDT")).thenReturn(Optional.empty());

        PositionOpsService.OpResult.Failed failed =
                (PositionOpsService.OpResult.Failed) service.closeAll("acc1", "BTCUSDT");

        assertThat(failed.code()).isEqualTo("NO_OPEN_POSITIONS");
        assertThat(failed.params()).containsEntry("symbol", "BTCUSDT");
        assertThat(failed.reason()).isEqualTo("no open positions for BTCUSDT");
    }
}
