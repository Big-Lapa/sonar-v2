package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.SymbolBlacklistEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SymbolBlacklistRepository extends JpaRepository<SymbolBlacklistEntity, String> {
}
