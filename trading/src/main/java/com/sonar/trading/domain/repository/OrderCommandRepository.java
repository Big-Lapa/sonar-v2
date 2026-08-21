package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.OrderCommandEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderCommandRepository extends JpaRepository<OrderCommandEntity, Long> {
    Optional<OrderCommandEntity> findByCommandId(String commandId);

    /** Used by {@link com.sonar.trading.signal.PendingSubmitRecoveryService}
     *  to find crash-survivor PENDING_SUBMIT commands older than the staleness threshold. */
    List<OrderCommandEntity> findByStatusAndCreatedAtBefore(String status, long createdAtBefore);
}
