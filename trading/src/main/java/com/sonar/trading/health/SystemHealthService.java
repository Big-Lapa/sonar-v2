package com.sonar.trading.health;

import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * v2 has no market-data stream or Kafka consumer lag to track — this only mirrors each
 * account's execution circuit-breaker state, which {@link com.sonar.trading.execution.gateway
 * .ResilientExecutionGateway} needs so an open circuit can be surfaced to an operator. Not a risk
 * gate itself in v2 (no SystemHealthGateRule — reconsidered and dropped per 3.1).
 */
@Service
public class SystemHealthService {

    public static final String DEFAULT_ACCOUNT = "default";

    private final ConcurrentHashMap<String, Boolean> circuitOpenByAccount = new ConcurrentHashMap<>();

    public void setExecutionCircuitOpen(String accountId, boolean open) {
        circuitOpenByAccount.put(accountId == null ? DEFAULT_ACCOUNT : accountId, open);
    }

    public boolean isExecutionCircuitOpen(String accountId) {
        return circuitOpenByAccount.getOrDefault(accountId == null ? DEFAULT_ACCOUNT : accountId, false);
    }
}
