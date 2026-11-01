package com.sonar.trading.execution;

import com.sonar.trading.domain.entity.DailyBaselineEntity;
import com.sonar.trading.domain.entity.PositionEntity;
import com.sonar.trading.domain.repository.DailyBaselineRepository;
import com.sonar.trading.domain.repository.PositionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Separate bean so {@code findForUpdate}'s row lock runs inside a real transaction (same
 * self-invocation trap as {@link ProposalClaimer} — {@link MinutePoller} calling its own
 * {@code @Transactional} method bypasses the Spring proxy).
 */
@Service
public class PollerTransactions {

    private static final Logger log = LoggerFactory.getLogger(PollerTransactions.class);

    private final DailyBaselineRepository dailyBaselineRepository;
    private final PositionRepository positionRepository;

    public PollerTransactions(DailyBaselineRepository dailyBaselineRepository, PositionRepository positionRepository) {
        this.dailyBaselineRepository = dailyBaselineRepository;
        this.positionRepository = positionRepository;
    }

    /** get-or-create today's row; equity_at_open is NEVER rewritten once created. */
    @Transactional
    public void upsertDailyBaseline(String accountId, LocalDate today, BigDecimal equity, BigDecimal realizedToday) {
        var existing = dailyBaselineRepository.findForUpdate(accountId, today);
        DailyBaselineEntity row;
        if (existing.isPresent()) {
            row = existing.get();
            BigDecimal peak = new BigDecimal(row.getPeakEquity());
            if (equity.compareTo(peak) > 0) {
                row.setPeakEquity(equity.toPlainString());
            }
        } else {
            BigDecimal priorPeak = dailyBaselineRepository.findFirstByIdAccountIdOrderByIdTradingDayDesc(accountId)
                    .map(b -> new BigDecimal(b.getPeakEquity())).orElse(BigDecimal.ZERO);
            BigDecimal peak = priorPeak.max(equity);
            row = DailyBaselineEntity.open(accountId, today, equity.toPlainString(), peak.toPlainString());
            log.info("[poller] {} opened daily_baseline for {} (equity_at_open={}, peak={})",
                    accountId, today, equity, peak);
        }
        if (realizedToday != null) {
            row.setRealizedPnl(realizedToday.toPlainString());
        }
        dailyBaselineRepository.save(row);
    }

    @Transactional
    public void closePosition(Long positionId) {
        positionRepository.findById(positionId).ifPresent(p -> {
            p.setStatus("CLOSED");
            p.setUpdatedAt(System.currentTimeMillis());
            positionRepository.save(p);
        });
    }

    /** Writes back SL/TP read from the exchange — for a position Sonar opened these were already
     *  known at entry, for an adopted one this is the only way they are ever learned. */
    @Transactional
    public void saveStopLevels(Long positionId, String slPrice, String tpPrice) {
        positionRepository.findById(positionId).ifPresent(p -> {
            p.setSlPrice(slPrice);
            p.setTpPrice(tpPrice);
            positionRepository.save(p);
        });
    }

    @Transactional
    public void saveQtyMismatch(Long positionId, boolean mismatch) {
        positionRepository.findById(positionId).ifPresent(p -> {
            p.setQtyMismatch(mismatch);
            positionRepository.save(p);
        });
    }
}
