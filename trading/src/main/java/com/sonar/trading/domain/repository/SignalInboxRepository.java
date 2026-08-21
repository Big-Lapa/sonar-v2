package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.SignalInboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SignalInboxRepository extends JpaRepository<SignalInboxEntity, Long> {
    Optional<SignalInboxEntity> findByIdempotencyKeyAndAccountId(String idempotencyKey, String accountId);
}
