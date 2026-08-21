package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.TradeCooldownEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface CooldownRepository extends JpaRepository<TradeCooldownEntity, Long> {

    Optional<TradeCooldownEntity> findByAccountIdAndSymbol(String accountId, String symbol);

    @Modifying
    @Query(value = "INSERT INTO trading.trade_cooldowns (account_id, symbol, last_loss_at) " +
                   "VALUES (:accountId, :symbol, :lastLossAt) " +
                   "ON CONFLICT (account_id, symbol) DO UPDATE SET last_loss_at = EXCLUDED.last_loss_at",
           nativeQuery = true)
    void upsert(String accountId, String symbol, long lastLossAt);
}
