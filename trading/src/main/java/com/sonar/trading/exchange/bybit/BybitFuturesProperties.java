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
    /** Fixed the OTHER way (grilled review, PR #2): {@code docker-compose.yml} now defaults
     *  {@code TRADING_BYBIT_BASE_URL} to this same testnet URL instead of blank, so a genuinely
     *  blank value here is a real (if broken) configuration intent, not compose's env-var-always-
     *  set quirk — swallowing it silently would let a real testnet/live misconfiguration through
     *  unnoticed, which is the one place a quiet fallback is the wrong failure mode. */
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

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
