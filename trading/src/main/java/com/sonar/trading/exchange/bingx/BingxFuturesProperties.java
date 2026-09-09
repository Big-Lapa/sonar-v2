package com.sonar.trading.exchange.bingx;

/**
 * BingX USDT-M perpetual futures (Swap V2) client properties.
 *
 * <p>Built per account by {@code AccountWiring} from that account's own credentials/base URL —
 * NOT a Spring-managed {@code @ConfigurationProperties} bean (it was, before credentials moved
 * off the venue; the annotation is gone because it was dead: nothing registers this class via
 * {@code @EnableConfigurationProperties} any more, so a {@code trading.bingx.*} entry in
 * application.yml — e.g. a custom {@code connect-timeout-ms} — would silently never apply).
 * {@code connectTimeoutMs}/{@code readTimeoutMs} below are therefore fixed Java defaults for
 * every account on this venue until per-account overrides are worth adding.
 */
public class BingxFuturesProperties {

    /** Base URL for BingX Futures Swap V2 (VST demo domain by default). */
    private String baseUrl = "https://open-api-vst.bingx.com";

    /** API key (from ENV / .env). */
    private String apiKey;

    /** API secret (from ENV / .env). */
    private String apiSecret;

    /** Recv window (ms) for signed requests; 0 disables the parameter. */
    private long recvWindow = 10_000L;

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
