package com.sonar.trading.risk;

import com.sonar.trading.config.AccountProps;
import com.sonar.trading.config.AccountRegistry;
import com.sonar.trading.domain.entity.AccountRiskEntity;
import com.sonar.trading.domain.entity.DailyBaselineEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.repository.AccountRiskRepository;
import com.sonar.trading.domain.repository.CooldownRepository;
import com.sonar.trading.domain.repository.DailyBaselineRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import com.sonar.trading.sizing.RiskBasedPositionSizer;
import com.sonar.trading.sizing.SizingResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Sizes and risk-gates a candidate LONG entry for ONE targeted account (manual multi-exchange
 * management — 4.8, POST /api/v1/strategy/proposals). Does NOT place any order; that's stage 3
 * (execution). Legacy fan-out across every tradable account (the LLM auto-search path) is kept
 * as the {@code accountId == null} case for now, but every current caller passes an explicit id.
 *
 * <p>Equity is sourced from {@code daily_baseline} (the minute poller, stage 4, keeps it fresh);
 * an account with no baseline row yet is rejected with an explicit reason rather than guessing.
 */
@Service
public class ProposalPreviewService {

    private final AccountRegistry accountRegistry;
    private final AccountRiskRepository accountRiskRepository;
    private final DailyBaselineRepository dailyBaselineRepository;
    private final PositionRepository positionRepository;
    private final CooldownRepository cooldownRepository;
    private final RiskEngine riskEngine;
    private final RiskBasedPositionSizer sizer;

    public ProposalPreviewService(AccountRegistry accountRegistry,
                                  AccountRiskRepository accountRiskRepository,
                                  DailyBaselineRepository dailyBaselineRepository,
                                  PositionRepository positionRepository,
                                  CooldownRepository cooldownRepository,
                                  RiskEngine riskEngine,
                                  RiskBasedPositionSizer sizer) {
        this.accountRegistry = accountRegistry;
        this.accountRiskRepository = accountRiskRepository;
        this.dailyBaselineRepository = dailyBaselineRepository;
        this.positionRepository = positionRepository;
        this.cooldownRepository = cooldownRepository;
        this.riskEngine = riskEngine;
        this.sizer = sizer;
    }

    /**
     * @param accountId targets exactly this account; null falls back to the legacy fan-out across
     *                  every account in the registry (kept for now, no current caller uses it).
     * @param manualNotional manual-trading-panel sizing: {@code qty = notional / entry} instead of
     *                       risk%-based sizing; null keeps the risk-based path. Risk GATES (auto
     *                       -pause, cooldown, position limits, portfolio risk, drawdown) still run
     *                       either way — only the qty formula changes.
     */
    @Transactional(readOnly = true)
    public List<AccountLegPreview> preview(String symbol, BigDecimal entry, BigDecimal sl, String accountId,
                                           BigDecimal manualNotional) {
        if (accountId != null) {
            return accountRegistry.byId(accountId)
                    .<List<AccountLegPreview>>map(account -> List.of(previewOne(account, symbol, entry, sl, manualNotional)))
                    .orElseGet(() -> List.of(AccountLegPreview.rejected(accountId, "аккаунт не найден в реестре")));
        }
        List<AccountLegPreview> legs = new ArrayList<>();
        for (AccountProps account : accountRegistry.all()) {
            legs.add(previewOne(account, symbol, entry, sl, manualNotional));
        }
        return legs;
    }

    private AccountLegPreview previewOne(AccountProps account, String symbol, BigDecimal entry, BigDecimal sl,
                                         BigDecimal manualNotional) {
        String accountId = account.id();
        AccountRiskEntity riskEntity = accountRiskRepository.findById(accountId)
                .orElseGet(() -> accountRiskRepository.save(AccountRiskEntity.defaultsFor(accountId)));
        if (!riskEntity.isTradable()) {
            return AccountLegPreview.rejected(accountId, "аккаунт выключен из торговли (tradable=false)");
        }
        AccountRiskProfile profile = AccountRiskProfile.from(riskEntity);

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        var baseline = dailyBaselineRepository.findByIdAccountIdAndIdTradingDay(accountId, today);
        if (baseline.isEmpty()) {
            return AccountLegPreview.rejected(accountId,
                    "нет данных по эквити на сегодня — дождитесь минутного поллера (этап 4)");
        }
        DailyBaselineEntity b = baseline.get();
        BigDecimal equity = new BigDecimal(b.getEquityAtOpen());
        BigDecimal peakEquity = new BigDecimal(b.getPeakEquity());
        BigDecimal realizedPnl = new BigDecimal(b.getRealizedPnl());

        PositionEntity currentPosition = positionRepository.findByAccountIdAndSymbol(accountId, symbol).orElse(null);
        long openCount = positionRepository.countOpenByAccountId(accountId);
        BigDecimal openPortfolioRisk = positionRepository.findAllOpenByAccountId(accountId).stream()
                .map(PositionEntity::getPlannedRiskAmount)
                .filter(v -> v != null && !v.isBlank())
                .map(BigDecimal::new)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Long lastLossAt = cooldownRepository.findByAccountIdAndSymbol(accountId, symbol)
                .map(c -> c.getLastLossAt()).orElse(null);
        boolean autoPaused = riskEngine.isAutoPauseActive(accountId);

        SizingResult sizing = manualNotional != null
                ? sizer.sizeManual(profile, account.id(), symbol, equity, manualNotional, entry, sl)
                : sizer.size(profile, account.id(), symbol, equity, entry, sl);
        BigDecimal candidateRisk = sizing instanceof SizingResult.Approved a ? a.actualRisk() : null;

        RiskContext ctx = new RiskContext(
                accountId, symbol, "OPEN", "LONG", profile, currentPosition, openCount,
                equity, equity, peakEquity, realizedPnl, openPortfolioRisk, candidateRisk,
                autoPaused, lastLossAt);
        RiskDecision decision = riskEngine.evaluate(ctx);

        if (decision.outcome() != RiskDecision.Outcome.APPROVE) {
            return AccountLegPreview.rejected(accountId, decision.reason());
        }
        if (sizing instanceof SizingResult.Rejected r) {
            return AccountLegPreview.rejected(accountId, r.reason());
        }
        SizingResult.Approved approved = (SizingResult.Approved) sizing;
        return AccountLegPreview.approved(accountId, approved.qty(), approved.actualRisk());
    }
}
