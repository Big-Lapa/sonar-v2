package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.StrategyStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StrategyStateRepository extends JpaRepository<StrategyStateEntity, Long> {
}
