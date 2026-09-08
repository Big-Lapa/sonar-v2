package com.sonar.trading.config;

/**
 * One entry of the account registry (11: {@code TRADING_ACCOUNTS_N_*} in `.env`).
 *
 * <p>Credentials and base URL are <b>per account</b>, not per venue: the same exchange can host
 * several distinct accounts (a BingX VST demo and a BingX production account, say), and each one
 * must reach its own domain with its own key. Nothing here falls back to a venue-wide default —
 * a demo default silently serving a real-money account is exactly the failure this shape exists
 * to prevent, so {@code baseUrl} is required whenever credentials are present (enforced at
 * startup by {@code AccountWiring}).
 *
 * @param apiKey    null/blank for an account whose keys haven't been filled in yet — it stays
 *                  visible in the panel but gets no client wired (see {@link #isConfigured()})
 * @param apiSecret paired with {@code apiKey}; both or neither
 * @param baseUrl   the venue domain this specific account talks to
 */
public record AccountProps(String id, String exchange, String apiKey, String apiSecret, String baseUrl) {

    /** True once both credentials are present — only then is a client built for this account. */
    public boolean isConfigured() {
        return notBlank(apiKey) && notBlank(apiSecret);
    }

    public boolean hasBaseUrl() {
        return notBlank(baseUrl);
    }

    /** First 4 characters of the key, for the startup wiring log — never the whole secret. */
    public String keyFingerprint() {
        if (!notBlank(apiKey)) {
            return "—";
        }
        return apiKey.length() <= 4 ? apiKey : apiKey.substring(0, 4) + "…";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
