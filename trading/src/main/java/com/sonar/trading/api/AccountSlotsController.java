package com.sonar.trading.api;

import com.sonar.trading.config.AccountProps;
import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.entity.AccountRiskEntity;
import com.sonar.trading.domain.entity.DailyBaselineEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.repository.AccountRiskRepository;
import com.sonar.trading.domain.repository.DailyBaselineRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.risk.RiskEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * {@code GET /api/v1/accounts/slots} — per tradable account: free slots, equity, auto-pause
 * state, open symbols. Drives the search-prompt gate (5: "there is a free slot") and the universe
 * filter (exclude symbols already open on any account).
 */
@RestController
@RequestMapping("/api/v1/accounts/slots")
public class AccountSlotsController {

    private final AccountRegistry accountRegistry;
    private final AccountRiskRepository accountRiskRepository;
    private final DailyBaselineRepository dailyBaselineRepository;
    private final PositionRepository positionRepository;
    private final RiskEngine riskEngine;

    public AccountSlotsController(AccountRegistry accountRegistry, AccountRiskRepository accountRiskRepository,
                                  DailyBaselineRepository dailyBaselineRepository,
                                  PositionRepository positionRepository, RiskEngine riskEngine) {
        this.accountRegistry = accountRegistry;
        this.accountRiskRepository = accountRiskRepository;
        this.dailyBaselineRepository = dailyBaselineRepository;
        this.positionRepository = positionRepository;
        this.riskEngine = riskEngine;
    }

    public record AccountSlot(String accountId, boolean tradable, int freeSlots, int maxOpenPositions,
                              String equity, boolean autoPaused, String autoPauseCause, List<String> openSymbols) {}

    @GetMapping
    public List<AccountSlot> slots() {
        return accountRegistry.all().stream().map(this::toSlot).toList();
    }

    private AccountSlot toSlot(AccountProps account) {
        String accountId = account.id();
        AccountRiskEntity risk = accountRiskRepository.findById(accountId).orElse(null);
        boolean tradable = risk != null && risk.isTradable();
        int maxOpen = risk != null ? risk.getMaxOpenPositions() : 0;

        List<PositionEntity> open = positionRepository.findAllOpenByAccountId(accountId);
        List<String> openSymbols = open.stream().map(PositionEntity::getSymbol).toList();
        int freeSlots = Math.max(0, maxOpen - open.size());

        String equity = dailyBaselineRepository
                .findByIdAccountIdAndIdTradingDay(accountId, LocalDate.now(ZoneOffset.UTC))
                .map(DailyBaselineEntity::getEquityAtOpen)
                .orElse(null);

        return new AccountSlot(accountId, tradable, tradable ? freeSlots : 0, maxOpen, equity,
                riskEngine.isAutoPauseActive(accountId),
                riskEngine.autoPauseCause(accountId) != null ? riskEngine.autoPauseCause(accountId).name() : null,
                openSymbols);
    }
}
