package com.sonar.trading.exchange.bybit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * Bybit V5 request signing (HMAC-SHA256). Unlike BingX (query-string only, GET-style even for
 * writes), Bybit V5 signs {@code timestamp + apiKey + recvWindow + payload}, where payload is the
 * raw query string for GET and the raw JSON body for POST — no key sorting, no double-encoding
 * gotcha. Headers carry the signature ({@code X-BAPI-SIGN}), not the URL.
 *
 * <p>Stateless utility; timestamp is passed in so signing stays deterministic and testable.
 */
public final class BybitRequestSigner {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private BybitRequestSigner() {}

    /**
     * @param payload raw query string (GET, no leading {@code ?}) or raw JSON body (POST) — exactly
     *                what will be sent on the wire, since the signature covers it byte-for-byte.
     */
    public static String sign(String payload, String secret, String apiKey, long recvWindow, long timestamp) {
        String toSign = timestamp + apiKey + recvWindow + (payload == null ? "" : payload);
        return hmacSha256Hex(toSign, secret);
    }

    /** HMAC-SHA256 of {@code message} keyed by {@code secret}, lower-case hex. */
    public static String hmacSha256Hex(String message, String secret) {
        if (secret == null) {
            secret = "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));

            char[] hexChars = new char[sig.length * 2];
            for (int j = 0; j < sig.length; j++) {
                int v = sig[j] & 0xFF;
                hexChars[j * 2] = HEX[v >>> 4];
                hexChars[j * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(hexChars);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC error", e);
        }
    }
}
