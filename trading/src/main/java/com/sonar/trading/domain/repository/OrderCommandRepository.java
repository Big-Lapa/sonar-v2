package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.OrderCommandEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderCommandRepository extends JpaRepository<OrderCommandEntity, Long> {
    Optional<OrderCommandEntity> findByCommandId(String commandId);

    /** Crash-survivor PENDING commands older than a staleness threshold (stage-4 stop-watchdog territory). */
    List<OrderCommandEntity> findByStatusAndCreatedAtBefore(String status, long createdAtBefore);

    /** Rebuilds the confirm report for a proposal already CONFIRMED by a prior/concurrent call. */
    List<OrderCommandEntity> findByProposalId(String proposalId);
}
