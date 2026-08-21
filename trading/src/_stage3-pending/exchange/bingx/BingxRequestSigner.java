package com.sonar.trading.exchange.bingx;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * BingX Swap V2 request signing (HMAC-SHA256) — ported from the working CryptoSonar prototype.
 *
 * <p>The hard-won gotcha lives here: BingX signs the <b>raw</b> (non URL-encoded) query string,
 * but the request URL must carry the <b>URL-encoded</b> values. Both strings use the same
 * key order (sorted), with {@code recvWindow} and {@code timestamp} appended before signing.
 *
 * <p>Stateless utility; the timestamp is passed in so the signing is deterministic and testable.
 */
public final class BingxRequestSigner {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private BingxRequestSigner() {}

    /**
     * Builds the signed query string for a BingX Swap V2 request:
     * sorted business params + {@code recvWindow} (if &gt; 0) + {@code timestamp}, with
     * {@code &signature=<hmac>} appended. Values in the returned string are URL-encoded.
     */
    public static String signedQuery(Map<String, String> params, String secret, long recvWindow, long timestamp) {
        // Sort canonically by key; recvWindow/timestamp participate in both digest and URL.
        TreeMap<String, String> sorted = new TreeMap<>();
        if (params != null) {
            sorted.putAll(params);
        }
        if (recvWindow > 0) {
            sorted.put("recvWindow", String.valueOf(recvWindow));
        }
        sorted.put("timestamp", String.valueOf(timestamp));

        String messageToDigest = buildQueryStringRaw(sorted);
        String signature = hmacSha256Hex(messageToDigest, secret);
        return buildQueryStringEncoded(sorted) + "&signature=" + signature;
    }

    /** Query-string for the signature: {@code key=value}, no URL-encoding. Params must be pre-sorted. */
    static String buildQueryStringRaw(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            first = false;
            String value = entry.getValue() == null ? "" : entry.getValue();
            sb.append(entry.getKey()).append("=").append(value);
        }
        return sb.toString();
    }

    /** Query-string for the URL: {@code key=urlEncode(value)}. Params must be pre-sorted. */
    static String buildQueryStringEncoded(Map<String, String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            first = false;
            String value = entry.getValue() == null ? "" : entry.getValue();
            sb.append(entry.getKey()).append("=").append(urlEncodeValue(value));
        }
        return sb.toString();
    }

    /** form-encoding nudged towards RFC3986: spaces as {@code %20} (critical for JSON values). */
    static String urlEncodeValue(String value) {
        String encoded = URLEncoder.encode(value, StandardCharsets.UTF_8);
        return encoded.replace("+", "%20");
    }

    /** HMAC-SHA256 of {@code payload} keyed by {@code secret}, lower-case hex (as in BingX docs). */
    public static String hmacSha256Hex(String payload, String secret) {
        if (secret == null) {
            secret = "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));

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
