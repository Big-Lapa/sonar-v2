package com.sonar.trading.exchange.binance;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binance USDT-M Futures request signing (HMAC-SHA256).
 *
 * <p>Unlike BingX (which signs a raw string but sends a URL-encoded one), Binance signs the
 * <b>exact</b> query string it receives. So a single canonical string is built with URL-encoded
 * values, the HMAC is computed over that same string, and {@code &signature=<hmac>} is appended.
 *
 * <p>Insertion order matters only for readability; Binance does not require sorted keys — it
 * validates the signature against the literal {@code queryString} received. {@code timestamp} and
 * (optionally) {@code recvWindow} are appended before signing. Stateless; timestamp is passed in
 * so signing is deterministic and testable.
 */
public final class BinanceRequestSigner {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private BinanceRequestSigner() {}

    /**
     * Builds the signed query string: business params + {@code recvWindow} (if &gt; 0) +
     * {@code timestamp}, with {@code &signature=<hmac>} appended. Values are URL-encoded and the
     * HMAC is computed over the encoded string exactly as sent.
     */
    public static String signedQuery(Map<String, String> params, String secret, long recvWindow, long timestamp) {
        Map<String, String> all = new LinkedHashMap<>();
        if (params != null) {
            all.putAll(params);
        }
        if (recvWindow > 0) {
            all.put("recvWindow", String.valueOf(recvWindow));
        }
        all.put("timestamp", String.valueOf(timestamp));

        String queryString = buildQueryStringEncoded(all);
        String signature = hmacSha256Hex(queryString, secret);
        return queryString + "&signature=" + signature;
    }

    /** Query-string for the URL and the signature: {@code key=urlEncode(value)}, insertion order. */
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

    /** form-encoding nudged towards RFC3986: spaces as {@code %20}. */
    public static String urlEncodeValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** HMAC-SHA256 of {@code payload} keyed by {@code secret}, lower-case hex (as Binance requires). */
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
