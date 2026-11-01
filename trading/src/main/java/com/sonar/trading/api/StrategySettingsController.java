package com.sonar.trading.api;

import com.sonar.trading.domain.entity.StrategySettingsEntity;
import com.sonar.trading.domain.repository.StrategySettingsRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

/** {@code GET/PUT /api/v1/strategy/settings} — gates/thresholds `strategy` reads before searching. */
@RestController
@RequestMapping("/api/v1/strategy/settings")
public class StrategySettingsController {

    // keep in sync: admin.html LLM_MODELS, model-pricing.ts RATES (TS), candidate-search.service.ts ADAPTIVE_THINKING_MODELS (TS)
    private static final Set<String> ALLOWED_MODELS = Set.of(
            "claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5-20251001");

    private final StrategySettingsRepository repository;

    public StrategySettingsController(StrategySettingsRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public StrategySettingsEntity get() {
        return repository.findById(StrategySettingsEntity.SINGLETON_ID)
                .orElseGet(() -> repository.save(StrategySettingsEntity.defaults()));
    }

    public record UpdateRequest(
            String minSlDistancePct, String minSlCostMult, String maxSlDistancePct, String minRr,
            String min24hVolumeUsd, String maxMove24hPct, String maxMove7dPct, Integer maxCandidates,
            String llmModel, Boolean alwaysDrawTv, Long proposalTtlMs, Integer holdReminderDays,
            String searchCronHours, int version) {}

    @PutMapping
    public ResponseEntity<?> update(@RequestBody UpdateRequest req) {
        StrategySettingsEntity e = repository.findById(StrategySettingsEntity.SINGLETON_ID)
                .orElseGet(StrategySettingsEntity::defaults);
        if (e.getVersion() != req.version()) {
            throw new OptimisticLockingFailureException(
                    "strategy settings changed since read (expected version " + req.version()
                            + ", actual " + e.getVersion() + ")");
        }
        if (req.minSlDistancePct() != null) e.setMinSlDistancePct(req.minSlDistancePct());
        if (req.minSlCostMult() != null) e.setMinSlCostMult(req.minSlCostMult());
        if (req.maxSlDistancePct() != null) e.setMaxSlDistancePct(req.maxSlDistancePct());
        if (req.minRr() != null) e.setMinRr(req.minRr());
        if (req.min24hVolumeUsd() != null) e.setMin24hVolumeUsd(req.min24hVolumeUsd());
        if (req.maxMove24hPct() != null) e.setMaxMove24hPct(req.maxMove24hPct());
        if (req.maxMove7dPct() != null) e.setMaxMove7dPct(req.maxMove7dPct());
        if (req.maxCandidates() != null) e.setMaxCandidates(req.maxCandidates());
        if (req.llmModel() != null) {
            if (!ALLOWED_MODELS.contains(req.llmModel())) {
                return ResponseEntity.badRequest().body("unknown llmModel: " + req.llmModel());
            }
            e.setLlmModel(req.llmModel());
        }
        if (req.alwaysDrawTv() != null) e.setAlwaysDrawTv(req.alwaysDrawTv());
        if (req.proposalTtlMs() != null) e.setProposalTtlMs(req.proposalTtlMs());
        if (req.holdReminderDays() != null) e.setHoldReminderDays(req.holdReminderDays());
        if (req.searchCronHours() != null) e.setSearchCronHours(req.searchCronHours());
        e.setUpdatedAt(System.currentTimeMillis());
        return ResponseEntity.ok(repository.save(e));
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<String> onConflict(OptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
