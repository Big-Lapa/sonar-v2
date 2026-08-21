package com.sonar.trading.api;

import com.sonar.trading.domain.entity.ProposalEntity;
import com.sonar.trading.domain.entity.StrategyStateEntity;
import com.sonar.trading.domain.repository.ProposalRepository;
import com.sonar.trading.domain.repository.StrategyStateRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 4.8: {@code GET/PUT /api/v1/strategy/state} — `strategy` is stateless, phase lives here (5). */
@RestController
@RequestMapping("/api/v1/strategy/state")
public class StrategyStateController {

    private final StrategyStateRepository stateRepository;
    private final ProposalRepository proposalRepository;

    public StrategyStateController(StrategyStateRepository stateRepository, ProposalRepository proposalRepository) {
        this.stateRepository = stateRepository;
        this.proposalRepository = proposalRepository;
    }

    public record StateResponse(String phase, Long promptMessageId, Long promptSentAt, List<ProposalEntity> pending) {}

    @GetMapping
    public StateResponse get() {
        StrategyStateEntity state = stateRepository.findById(StrategyStateEntity.SINGLETON_ID)
                .orElseGet(() -> stateRepository.save(StrategyStateEntity.idle()));
        List<ProposalEntity> pending = proposalRepository.findByStatusOrderByCreatedAtAsc("PENDING");
        return new StateResponse(state.getPhase(), state.getPromptMessageId(), state.getPromptSentAt(), pending);
    }

    public record UpdateRequest(String phase, Long promptMessageId, Long promptSentAt) {}

    @PutMapping
    public StateResponse update(@RequestBody UpdateRequest req) {
        StrategyStateEntity state = stateRepository.findById(StrategyStateEntity.SINGLETON_ID)
                .orElseGet(() -> StrategyStateEntity.idle());
        if (req.phase() != null) state.setPhase(req.phase());
        state.setPromptMessageId(req.promptMessageId());
        state.setPromptSentAt(req.promptSentAt());
        state.setUpdatedAt(System.currentTimeMillis());
        stateRepository.save(state);
        return get();
    }
}
