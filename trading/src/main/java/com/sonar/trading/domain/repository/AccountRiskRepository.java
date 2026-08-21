package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.AccountRiskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRiskRepository extends JpaRepository<AccountRiskEntity, String> {
}
