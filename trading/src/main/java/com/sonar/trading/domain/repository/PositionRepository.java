package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.PositionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PositionRepository extends JpaRepository<PositionEntity, Long> {
    Optional<PositionEntity> findByAccountIdAndSymbol(String accountId, String symbol);

    @Query("SELECT p FROM PositionEntity p WHERE p.status = 'OPEN'")
    List<PositionEntity> findAllOpen();

    @Query("SELECT p FROM PositionEntity p WHERE p.status = 'OPEN' AND p.accountId = :accountId")
    List<PositionEntity> findAllOpenByAccountId(String accountId);

    @Query("SELECT COUNT(p) FROM PositionEntity p WHERE p.status = 'OPEN' AND p.accountId = :accountId")
    long countOpenByAccountId(String accountId);

    /** Открытые позиции по всем аккаунтам — драйвер адаптивной каденции recon. */
    @Query("SELECT COUNT(p) FROM PositionEntity p WHERE p.status = 'OPEN'")
    long countOpen();
}
