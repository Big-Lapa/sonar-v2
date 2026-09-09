package com.sonar.trading.exchange.binance;

/**
 * Binance USDT-M perpetual futures client properties.
 *
 * <p>Testnet domain by default (same principle as BingX's VST demo / Bybit's testnet, ADR-0010) —
 * mainnet only via explicit {@code baseUrl} override. Secrets from environment / {@code .env}
 * (ADR-0021).
 *
 * <p>Built per account by {@code AccountWiring}, not a Spring-managed
 * {@code @ConfigurationProperties} bean — mirrors {@code BybitFuturesProperties}: nothing binds
 * this via {@code @EnableConfigurationProperties} any more, so a {@code trading.binance.*} entry
 * in application.yml would silently never apply. {@code recvWindow}/timeouts are fixed Java
 * defaults for every account on this venue.
 */
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
