package com.sonar.trading.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Token gate for the operator HTTP API (/api/v1/** — account ops + manual trade).
 * Until ADMIN_TOKEN is set every /api/v1 endpoint answers 503 — safe default: close-all
 * and manual orders are unreachable over HTTP. The Kafka signal pipeline is unaffected.
 * Same operator secret as config-service admin API and the monitoring admin relay.
 */
@ConfigurationProperties(prefix = "trading.ops")
public record OpsApiProperties(String token) {

    public boolean enabled() {
        return token != null && !token.isBlank();
    }
}
