package com.sonar.trading.execution;

import com.sonar.trading.domain.repository.ProposalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Separate bean so the {@code @Modifying} claim query runs inside a real transaction (Spring
 * Data JPA requires one) without wrapping all of {@link ProposalExecutionService#confirm} —
 * that method holds a DB connection across live exchange HTTP calls and multi-second polling
 * sleeps, which a single big transaction would hold open for far too long.
 */
@Service
public class ProposalClaimer {

    private final ProposalRepository proposals;

    public ProposalClaimer(ProposalRepository proposals) {
        this.proposals = proposals;
    }

    @Transactional
    public boolean claim(String proposalId) {
        return proposals.claim(proposalId, System.currentTimeMillis()) > 0;
    }
}
