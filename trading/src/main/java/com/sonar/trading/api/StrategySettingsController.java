package com.sonar.trading.api;

import com.sonar.trading.domain.entity.StrategySettingsEntity;
import com.sonar.trading.domain.repository.StrategySettingsRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 4.8: {@code GET /api/v1/strategy/settings} — gates/thresholds `strategy` reads before searching. */
@RestController
@RequestMapping("/api/v1/strategy/settings")
public class StrategySettingsController {

    private final StrategySettingsRepository repository;

    public StrategySettingsController(StrategySettingsRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public StrategySettingsEntity get() {
        return repository.findById(StrategySettingsEntity.SINGLETON_ID)
                .orElseGet(() -> repository.save(StrategySettingsEntity.defaults()));
    }
}
