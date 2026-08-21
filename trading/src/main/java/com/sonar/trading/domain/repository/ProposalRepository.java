package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.ProposalEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProposalRepository extends JpaRepository<ProposalEntity, String> {
}
