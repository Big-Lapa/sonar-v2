package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.FillEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FillRepository extends JpaRepository<FillEntity, Long> {
    boolean existsByOrderId(Long orderId);
}
