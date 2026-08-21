package com.sonar.trading.exchange.bingx.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sonar.trading.exchange.bingx.BingxFuturesClient;
import com.sonar.trading.exchange.bingx.BingxFuturesProperties;
import com.sonar.trading.exchange.bingx.BingxPlaceOrderRequest;
import com.sonar.trading.exchange.bingx.BingxRequestSigner;
import com.sonar.trading.exchange.dto.ExchangeError;
import com.sonar.trading.exchange.exception.ExchangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BingX USDT-M perpetual futures (Swap V2) signed HTTP client — ported from the working
 * CryptoSonar prototype (T12). VST demo domain by default (ADR-0010), secrets from .env (ADR-0021).
 *
 * <p>Signing (the double query-string gotcha) lives in {@link BingxRequestSigner}. Endpoint
 * methods return the raw decoded JSON as {@code Map<String,Object>}; mapping into the platform's
 * {@code AdapterOrderResult} happens in {@code BingxOrderMapper} / {@code LiveExecutionAdapter} (T13).
 *
 * <p>Uses plain {@link HttpURLConnection} (no third-party HTTP client dependency). Not a Spring
 * bean by itself — wired conditionally for {@code trading.exchange=LIVE} via {@code BingxClientConfig}.
 */
public class BingxFuturesHttpService implements BingxFuturesClient {

    private static final Logger log = LoggerFactory.getLogger(BingxFuturesHttpService.class);

    private final BingxFuturesProperties props;

    private final ObjectMapper mapper = new ObjectMapper()
            // Canonical JSON: stable field order
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            // Keep BigDecimal out of scientific notation
            .configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, true);

    public BingxFuturesHttpService(BingxFuturesProperties props) {
        this.props = props;
    }

    // ---- Trade endpoints ----

    /**
     * Place an order on the specified Swap V2 contract (limit/market). When {@code positionSide}
     * is null it defaults to {@code BOTH} (the platform runs the account in One-Way Mode, BUG-1/T22).
     *
     * <p>Non-idempotent: a read timeout here is <em>ambiguous</em> (the exchange may have accepted
     * the order) and is surfaced as {@code IO_ERROR_AMBIGUOUS}, which the gateway never retries —
     * BingX does not document clientOrderId dedup, so a blind retry could double the position.
     */
    public Map<String, Object> placeOrder(BingxPlaceOrderRequest request) {
        return parseJsonToMap(executeSigned("POST", "/openApi/swap/v2/trade/order",
                buildPlaceOrderQuery(request), true));
    }

    /**
     * Assemble the signed query parameters for a place-order call.
     *
     * <p>Package-private so it can be unit-tested without HTTP. {@code positionSide} defaults to
     * {@code BOTH}: the platform is one-way (a single position per symbol), so every order — open
     * BUY, close SELL, open-short SELL, close-short BUY — must carry {@code BOTH}. Deriving
     * LONG/SHORT from {@code side} (the old behaviour) only works in hedge mode and silently
     * inverted CLOSE legs into new opposite positions on a hedge account (BUG-1, T22).
     */
    static Map<String, String> buildPlaceOrderQuery(BingxPlaceOrderRequest request) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("symbol", request.symbol());
        q.put("side", request.side().name());
        q.put("type", request.type().name());
        q.put("quantity", request.quantity());

        String positionSide = request.positionSide() != null ? request.positionSide().name() : "BOTH";
        q.put("positionSide", positionSide);

        if (request.price() != null) {
            q.put("price", request.price());
            if (request.timeInForce() != null) {
                q.put("timeInForce", request.timeInForce().name());
            }
        }
        if (request.clientOrderId() != null) {
            q.put("clientOrderId", request.clientOrderId());
        }
        if (request.reduceOnly()) {
            // One-way mode: CLOSE/REDUCE legs must never open or flip a position — a concurrent
            // duplicate close (analyzer CLOSE vs operator close-all) otherwise nets into a short.
            q.put("reduceOnly", "true");
        }
        if (request.stopPrice() != null) {
            q.put("stopPrice", request.stopPrice());
        }
        if (request.takeProfit() != null) {
            q.put("takeProfit", request.takeProfit().toJson());
        }
        if (request.stopLoss() != null) {
            q.put("stopLoss", request.stopLoss().toJson());
        }
        return q;
    }

    /** Cancel an order currently in an entrusted state. */
    public Map<String, Object> cancelOrder(String symbol, String orderId, String clientOrderId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("symbol", symbol);
        if (orderId != null) {
            q.put("orderId", orderId);
        }
        if (clientOrderId != null) {
            q.put("clientOrderId", clientOrderId);
        }
        // Swap V2 cancel is DELETE on the same /trade/order resource (not /trade/cancelOrder,
        // which returns 100404 "this api is not exist").
        return parseJsonToMap(deleteSigned("/openApi/swap/v2/trade/order", q));
    }

    /** Query order details by orderId and/or clientOrderId. */
    public Map<String, Object> getOrder(String symbol, String orderId, String clientOrderId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("symbol", symbol);
        if (orderId != null) {
            q.put("orderId", orderId);
        }
        if (clientOrderId != null) {
            q.put("clientOrderId", clientOrderId);
        }
        return parseJsonToMap(getSigned("/openApi/swap/v2/trade/order", q));
    }

    /** Close all positions in bulk (optionally scoped to a symbol). */
    public Map<String, Object> closeAllPositions(String symbol) {
        Map<String, String> q = new LinkedHashMap<>();
        if (symbol != null) {
            q.put("symbol", symbol);
        }
        return parseJsonToMap(postSigned("/openApi/swap/v2/trade/closeAllPositions", q));
    }

    // ---- Account / market endpoints ----

    /** All open orders for a symbol, including pending TP/SL conditional orders. */
    public Map<String, Object> getOpenOrders(String symbol) {
        Map<String, String> q = new LinkedHashMap<>();
        if (symbol != null && !symbol.isBlank()) {
            q.put("symbol", symbol);
        }
        return parseJsonToMap(getSigned("/openApi/swap/v2/trade/openOrders", q));
    }

    /** Current positions (optionally scoped to a symbol). */
    public Map<String, Object> getPositions(String symbol) {
        Map<String, String> q = new LinkedHashMap<>();
        if (symbol != null && !symbol.isBlank()) {
            q.put("symbol", symbol);
        }
        return parseJsonToMap(getSigned("/openApi/swap/v2/user/positions", q));
    }

    /** Account balance (Swap V3). */
    public Map<String, Object> getBalance() {
        return parseJsonToMap(getSigned("/openApi/swap/v3/user/balance", Collections.emptyMap()));
    }

    /** Account income ledger (Swap V2); {@code incomeType} e.g. FUNDING_FEE. Nulls omit the param. */
    public Map<String, Object> getIncome(String incomeType, Long startTime, Long endTime, Integer limit) {
        return parseJsonToMap(getSigned("/openApi/swap/v2/user/income",
                buildIncomeQuery(incomeType, startTime, endTime, limit)));
    }

    /** Assemble the income query params (package-private for unit tests; nulls are omitted). */
    static Map<String, String> buildIncomeQuery(String incomeType, Long startTime, Long endTime, Integer limit) {
        Map<String, String> q = new LinkedHashMap<>();
        if (incomeType != null) {
            q.put("incomeType", incomeType);
        }
        if (startTime != null) {
            q.put("startTime", String.valueOf(startTime));
        }
        if (endTime != null) {
            q.put("endTime", String.valueOf(endTime));
        }
        if (limit != null) {
            q.put("limit", String.valueOf(limit));
        }
        return q;
    }

    /** Latest price for a symbol; {@code type} optional (e.g. mark/last). */
    public Map<String, Object> getPrice(String symbol, String type) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("symbol", symbol);
        if (type != null) {
            q.put("type", type);
        }
        return parseJsonToMap(getSigned("/openApi/swap/v2/quote/price", q));
    }

    // ---- Signed HTTP helpers ----

    public String getSigned(String path, Map<String, String> query) {
        return executeSigned("GET", path, query);
    }

    public String postSigned(String path, Map<String, String> query) {
        return executeSigned("POST", path, query);
    }

    public String deleteSigned(String path, Map<String, String> query) {
        return executeSigned("DELETE", path, query);
    }

    private String executeSigned(String method, String path, Map<String, String> query) {
        return executeSigned(method, path, query, false);
    }

    /**
     * @param nonIdempotent when true, a read timeout (request may have reached the exchange) is
     *                      reported as {@code IO_ERROR_AMBIGUOUS} — never retried by the gateway;
     *                      connect-phase failures (nothing sent) stay retryable {@code IO_ERROR}.
     */
    private String executeSigned(String method, String path, Map<String, String> query,
                                 boolean nonIdempotent) {
        try {
            String fullQuery = BingxRequestSigner.signedQuery(
                    query, props.getApiSecret(), props.getRecvWindow(), System.currentTimeMillis());
            String urlStr = props.getBaseUrl() + path + "?" + fullQuery;
            return sendHttp(method, urlStr, true);
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException ioe) {
            String code = (nonIdempotent && !isConnectPhaseFailure(ioe)) ? "IO_ERROR_AMBIGUOUS" : "IO_ERROR";
            throw new ExchangeException(new ExchangeError(code, ioe.getMessage()), ioe);
        } catch (Exception e) {
            throw new ExchangeException(new ExchangeError("BINGX_HTTP_ERROR", e.getMessage()), e);
        }
    }

    /** True when the failure happened before anything was sent (safe to retry even a place). */
    private static boolean isConnectPhaseFailure(IOException ioe) {
        if (ioe instanceof java.net.ConnectException || ioe instanceof java.net.UnknownHostException
                || ioe instanceof java.net.NoRouteToHostException) {
            return true;
        }
        String msg = ioe.getMessage();
        return msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("connect timed out");
    }

    // ---- Low-level HttpURLConnection helper ----

    private String sendHttp(String method, String urlStr, boolean signed) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            log.debug("BingX request: {} {}", method, redactSignature(urlStr));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(props.getConnectTimeoutMs());
            conn.setReadTimeout(props.getReadTimeoutMs());

            if (signed) {
                conn.setRequestProperty("X-BX-APIKEY", nullToEmpty(props.getApiKey()));
            }

            if ("POST".equalsIgnoreCase(method)) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                // Body is empty: all parameters travel in the query string.
                conn.getOutputStream().write(new byte[0]);
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
            String body = null;
            if (is != null) {
                try (is) {
                    body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    log.debug("BingX response: {}", body);
                }
            }

            if (status < 200 || status >= 300) {
                throw toExchangeException(status, body, "HTTP " + status + " in BingX call");
            }
            return body == null ? "" : body;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---- Helpers ----

    private Map<String, Object> parseJsonToMap(String body) {
        if (body == null || body.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return requireOk(mapper.readValue(body, new TypeReference<Map<String, Object>>() {}));
        } catch (IOException e) {
            throw new ExchangeException(new ExchangeError("BINGX_PARSE_ERROR", e.getMessage()), e);
        }
    }

    /**
     * BingX reports business rejections as HTTP 200 with a non-zero {@code code} (the classic
     * "200 + code!=0" gotcha). Without this check a rejected order parses to a status-less map,
     * maps to state NEW and becomes an eternal zombie that reconciliation can never resolve.
     */
    static Map<String, Object> requireOk(Map<String, Object> root) {
        Object code = root.get("code");
        if (code == null) {
            return root;
        }
        String c = String.valueOf(code);
        if ("0".equals(c)) {
            return root;
        }
        Object m = root.get("msg") != null ? root.get("msg") : root.get("message");
        throw new ExchangeException(new ExchangeError(c,
                m != null ? String.valueOf(m) : "BingX business error code=" + c));
    }

    private ExchangeException toExchangeException(int status, String body, String fallbackMsg) {
        String code = String.valueOf(status);
        String message = fallbackMsg;
        try {
            if (body != null && !body.isBlank()) {
                Map<String, Object> root = mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
                Object c = root.get("code");
                if (c != null) {
                    code = String.valueOf(c);
                }
                Object m = (root.get("msg") != null ? root.get("msg") : root.get("message"));
                if (m != null) {
                    message = String.valueOf(m);
                } else if (root.get("data") != null) {
                    message = String.valueOf(root.get("data"));
                }
            }
        } catch (Exception ignore) {
            // keep fallback code/message
        }
        return new ExchangeException(new ExchangeError(code, message));
    }

    /** Hide the HMAC signature when logging URLs (same redaction as the Binance client). */
    private static String redactSignature(String url) {
        return url.replaceAll("signature=[0-9a-fA-F]+", "signature=***");
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
