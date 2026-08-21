package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.DailyBaselineEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface DailyBaselineRepository extends JpaRepository<DailyBaselineEntity, DailyBaselineEntity.Key> {

    Optional<DailyBaselineEntity> findByIdAccountIdAndIdTradingDay(String accountId, LocalDate tradingDay);

    /** Row-locked read for the accounting-update path (poller): serializes concurrent read-modify-write. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM DailyBaselineEntity b WHERE b.id.accountId = :accountId AND b.id.tradingDay = :tradingDay")
    Optional<DailyBaselineEntity> findForUpdate(@Param("accountId") String accountId,
                                                @Param("tradingDay") LocalDate tradingDay);

    /** Most recent row for an account — source of the running peak when opening a new day. */
    Optional<DailyBaselineEntity> findFirstByIdAccountIdOrderByIdTradingDayDesc(String accountId);
}
