package com.sonar.trading.exchange.pionex;


/**
 * Pionex Futures ({@code /uapi/v1}) client properties — read-only account access for now
 * (balance + positions only, see {@code PionexAccountReader}; order placement is a later
 * iteration). Secrets ({@code apiKey}/{@code apiSecret}) come from environment / {@code .env}
 * per ADR-0021 — never committed.
 *
 * <p>Unlike Bybit, Pionex has no separate demo API host: "demo" is a set of {@code *_PUSD_PERP}
 * paper-trading symbols on the same {@code api.pionex.com}, selected by the account/symbol, not
 * by base URL — so there is no live/demo base-url split to get wrong here (ADR context: the
 * Bybit 401 investigation earlier in this project was exactly that class of bug for a different
 * venue).
 *
 * <p>Built per account by {@code AccountWiring}, not a Spring-managed
 * {@code @ConfigurationProperties} bean — that annotation was removed because nothing registers
 * this class via {@code @EnableConfigurationProperties} any more, so it was dead: a
 * {@code trading.pionex.connect-timeout-ms} entry in application.yml would silently never apply.
 * The timeouts below are fixed Java defaults for every account on this venue.
 */
public class PionexFuturesProperties {

    /** Base URL — Pionex has one API host for both live and demo-symbol trading. */
    private String baseUrl = "https://api.pionex.com";

    /** API key (from ENV / .env). */
    private String apiKey;

    /** API secret (from ENV / .env). */
    private String apiSecret;

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

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}
