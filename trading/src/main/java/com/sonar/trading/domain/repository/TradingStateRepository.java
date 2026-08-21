package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.TradingStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradingStateRepository extends JpaRepository<TradingStateEntity, String> {

    /** All state rows whose key starts with the given prefix (e.g. per-account {@code auto_pause:}). */
    List<TradingStateEntity> findByKeyStartingWith(String prefix);
}
