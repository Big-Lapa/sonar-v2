package com.sonar.trading.exchange.bybit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bybit V5 USDT-perpetual ({@code category=linear}) client properties.
 *
 * <p>Testnet domain by default (grilled: same principle as BingX's VST demo, ADR-0010) — mainnet
 * only via explicit {@code baseUrl} override. Secrets from environment / {@code .env} (ADR-0021).
 */
@ConfigurationProperties(prefix = "trading.bybit")
public class BybitFuturesProperties {

    /** Base URL for Bybit V5 (testnet by default). */
    private String baseUrl = "https://api-testnet.bybit.com";

    /** API key (from ENV / .env). */
    private String apiKey;

    /** API secret (from ENV / .env). */
    private String apiSecret;

    /** Recv window (ms) for signed requests. */
    private long recvWindow = 10_000L;

    /** HTTP connect timeout (ms). */
    private int connectTimeoutMs = 3_000;

    /** HTTP read timeout (ms). */
    private int readTimeoutMs = 5_000;

    public String getBaseUrl() { return baseUrl; }
    /** Blank/null is a no-op (keeps the testnet default) — docker-compose always sets the env var
     *  key even when the host doesn't define it, which would otherwise blank out the default. */
    public void setBaseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            this.baseUrl = baseUrl;
        }
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }

    public long getRecvWindow() { return recvWindow; }
    public void setRecvWindow(long recvWindow) { this.recvWindow = recvWindow; }

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}
