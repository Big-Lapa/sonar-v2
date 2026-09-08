package com.sonar.trading.exchange.pionex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pionex Futures API request signing (HMAC-SHA256, hex) — ported from the working Sonar v1
 * implementation ({@code services/slippager/pionex/PionexSigner.java} in the sibling
 * {@code sonar-platform} repo), where it has been exercised against the real API. Per the
 * futures-api authentication doc:
 * <ol>
 *   <li>sort all query params (including {@code timestamp}) alphabetically, join {@code k=v} with
 *       {@code &}, <b>no URL-encoding</b> of values in the signed string;</li>
 *   <li>payload = {@code METHOD + PATH + "?" + sortedQuery}, and for POST/DELETE append the raw JSON
 *       body;</li>
 *   <li>signature = lower-case hex HMAC-SHA256 of the payload keyed by the API secret, sent in the
 *       {@code PIONEX-SIGNATURE} header ({@code PIONEX-KEY} carries the key).</li>
 * </ol>
 * Stateless and deterministic (timestamp passed in) — unit-testable without network.
 */
public final class PionexRequestSigner {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PionexRequestSigner() {}

    /** Sorted {@code k=v&...} query string with {@code timestamp} merged in. */
    public static String sortedQuery(Map<String, String> params, long timestampMs) {
        TreeMap<String, String> sorted = new TreeMap<>();
        if (params != null) {
            sorted.putAll(params);
        }
        sorted.put("timestamp", String.valueOf(timestampMs));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? "" : e.getValue());
        }
        return sb.toString();
    }

    /** Signature over {@code METHOD + path + "?" + sortedQuery (+ body)}. Body empty/null for GET. */
    public static String sign(String method, String path, String sortedQuery, String body, String secret) {
        String payload = method + path + "?" + sortedQuery + (body == null ? "" : body);
        return hmacSha256Hex(payload, secret);
    }

    /** HMAC-SHA256 of {@code payload} keyed by {@code secret}, lower-case hex. */
    static String hmacSha256Hex(String payload, String secret) {
        if (secret == null) {
            secret = "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[sig.length * 2];
            for (int i = 0; i < sig.length; i++) {
                int v = sig[i] & 0xFF;
                hex[i * 2] = HEX[v >>> 4];
                hex[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(hex);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC error", e);
        }
    }
}
