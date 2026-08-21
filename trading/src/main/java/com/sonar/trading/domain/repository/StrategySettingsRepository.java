package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.StrategySettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StrategySettingsRepository extends JpaRepository<StrategySettingsEntity, Long> {
}
