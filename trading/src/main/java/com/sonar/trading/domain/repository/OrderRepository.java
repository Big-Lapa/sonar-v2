package com.sonar.trading.domain.repository;

import com.sonar.trading.domain.entity.OrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<OrderEntity, Long> {
    Optional<OrderEntity> findByClientOrderId(String clientOrderId);

    @Query("SELECT o FROM OrderEntity o WHERE o.symbol = :symbol AND o.status NOT IN ('FILLED','REJECTED','CANCELLED','FAILED')")
    List<OrderEntity> findActiveBySymbol(String symbol);

    @Query("SELECT o FROM OrderEntity o WHERE o.accountId = :accountId AND o.symbol = :symbol AND o.status NOT IN ('FILLED','REJECTED','CANCELLED','FAILED')")
    List<OrderEntity> findActiveByAccountIdAndSymbol(String accountId, String symbol);

    @Query("SELECT o FROM OrderEntity o WHERE o.accountId = :accountId AND o.status NOT IN ('FILLED','REJECTED','CANCELLED','FAILED')")
    List<OrderEntity> findAllActiveByAccountId(String accountId);

    @Query("SELECT COUNT(o) FROM OrderEntity o WHERE o.status NOT IN ('FILLED','REJECTED','CANCELLED','FAILED')")
    long countActiveOrders();

    @Query("SELECT o FROM OrderEntity o WHERE o.status NOT IN ('FILLED','REJECTED','CANCELLED','FAILED')")
    List<OrderEntity> findAllActive();
}
