package com.sonar.trading.api;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Server-side gate for symbols written through the operator API (blacklist, proposals). Symbols
 * are later rendered in the admin panel and sent to exchanges, so anything outside the plain
 * ticker alphabet (e.g. HTML from a Telegram {@code /ban}) is refused at the door instead of
 * being stored.
 */
public final class Symbols {

    /**
     * Core tickers ({@code BTCUSDT}, {@code 1000PEPEUSDT}) plus the {@code -}/{@code _} some venues use.
     * Mirrored in strategy's {@code TICKER_RE} (telegram/access-guard.ts) — change both together.
     */
    private static final Pattern VALID = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,39}$");

    private Symbols() {}

    /** Trimmed, upper-cased symbol, or {@code null} when it isn't a plausible ticker. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toUpperCase(Locale.ROOT);
        return VALID.matcher(s).matches() ? s : null;
    }
}
