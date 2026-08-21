package com.sonar.trading.exchange.binance;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binance USDT-M perpetual futures client properties.
 *
 * <p>Testnet domain by default ({@code testnet.binancefuture.com}) — the Binance analogue of the
 * BingX VST demo. Secrets ({@code apiKey}/{@code apiSecret}) are injected from environment /
 * {@code .env} (git-ignored) per ADR-0021 — never committed.
 */
@ConfigurationProperties(prefix = "trading.binance")
public class BinanceFuturesProperties {

    /** Base URL for Binance USDT-M Futures (testnet by default). */
    private String baseUrl = "https://testnet.binancefuture.com";

    /** API key (from ENV / .env). */
    private String apiKey;

    /** API secret (from ENV / .env). */
    private String apiSecret;

    /** Recv window (ms) for signed requests; 0 disables the parameter. */
    private long recvWindow = 5_000L;

    /** HTTP connect timeout (ms). */
    private int connectTimeoutMs = 3_000;

    /** HTTP read timeout (ms). */
    private int readTimeoutMs = 5_000;

    public String getBaseUrl() { return baseUrl; }
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
