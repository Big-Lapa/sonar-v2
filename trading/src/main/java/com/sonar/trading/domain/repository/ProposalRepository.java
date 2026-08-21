package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.ProposalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProposalRepository extends JpaRepository<ProposalEntity, String> {

    List<ProposalEntity> findByStatusOrderByCreatedAtAsc(String status);

    /**
     * Idempotent claim (4.1/4.8): flips PENDING -> CONFIRMED atomically. Returns the number of
     * rows updated — 0 means someone else already confirmed/rejected it (a repeat tap), 1 means
     * this call owns execution.
     */
    @Modifying
    @Query("UPDATE ProposalEntity p SET p.status = 'CONFIRMED', p.decidedAt = :decidedAt "
            + "WHERE p.proposalId = :proposalId AND p.status = 'PENDING'")
    int claim(@Param("proposalId") String proposalId, @Param("decidedAt") long decidedAt);
}
