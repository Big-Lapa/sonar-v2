package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<NotificationEntity, Long> {
    List<NotificationEntity> findByIdGreaterThanOrderByIdAsc(long after);
}
