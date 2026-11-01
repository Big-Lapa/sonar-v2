package com.sonar.trading.exchange.binance.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sonar.trading.exchange.binance.BinanceFuturesClient;
import com.sonar.trading.exchange.binance.BinanceFuturesProperties;
import com.sonar.trading.exchange.binance.BinanceOrderMapper;
import com.sonar.trading.exchange.binance.BinancePlaceOrderRequest;
import com.sonar.trading.exchange.binance.BinanceRequestSigner;
import com.sonar.trading.exchange.dto.ExchangeError;
import com.sonar.trading.exchange.exception.ExchangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binance USDT-M Futures signed HTTP client (mirror of {@code BingxFuturesHttpService}).
 * Testnet domain by default; secrets from .env (ADR-0021).
 *
 * <p>Signing lives in {@link BinanceRequestSigner} (single query-string HMAC, unlike BingX's
 * double-encoding). Endpoint methods return the decoded JSON as {@code Map<String,Object>}; array
 * responses ({@code /positionRisk}, {@code /balance}) are wrapped under a {@code "data"} key so the
 * return type stays uniform. Mapping into {@code AdapterOrderResult} happens in the adapter.
 *
 * <p>Uses plain {@link HttpURLConnection}. Not a Spring bean by itself — wired conditionally for
 * {@code trading.exchange=BINANCE} via {@code BinanceClientConfig}.
 */
public class BinanceFuturesHttpService implements BinanceFuturesClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceFuturesHttpService.class);

    /** Binance rejects a signed request whose timestamp is &gt; serverTime + 1000ms (error -1021). */
    private static final String ERR_TIMESTAMP_AHEAD = "-1021";

    private final BinanceFuturesProperties props;

    /** serverTime - localTime, applied to every signed request's timestamp. Synced lazily. */
    private volatile long timeOffsetMs = 0L;
    private volatile boolean timeSynced = false;

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, true);

    public BinanceFuturesHttpService(BinanceFuturesProperties props) {
        this.props = props;
    }

    // ---- Trade endpoints ----

    /**
     * Place an order (limit/market). {@code positionSide} defaults to {@code BOTH} (One-Way Mode).
     *
     * <p>Non-idempotent: a read timeout here is <em>ambiguous</em> (the order may have reached the
     * exchange) and is surfaced as {@code IO_ERROR_AMBIGUOUS}, which the gateway never retries — a
     * blind retry with the same {@code newClientOrderId} could double the position. Connect-phase
     * failures (nothing sent) stay retryable {@code IO_ERROR}. Mirrors BingxFuturesHttpService.
     */
    public Map<String, Object> placeOrder(BinancePlaceOrderRequest request) {
        return parseJsonToMap(executeSigned("POST", "/fapi/v1/order", buildPlaceOrderQuery(request), true));
    }

    /**
     * Assemble the signed query parameters for a place-order call. Package-private for unit testing.
     *
     * <p>{@code positionSide} defaults to {@code BOTH} (One-Way Mode — every leg carries BOTH).
     * {@code price}/{@code timeInForce} are sent only for LIMIT. {@code reduceOnly} is sent only
     * when true (Binance rejects it alongside a non-BOTH positionSide, but One-Way keeps it BOTH).
     */
    static Map<String, String> buildPlaceOrderQuery(BinancePlaceOrderRequest request) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("symbol", request.symbol());
        q.put("side", request.side().name());
        q.put("type", request.type().name());
        q.put("quantity", request.quantity());

        String positionSide = request.positionSide() != null ? request.positionSide().name() : "BOTH";
        q.put("positionSide", positionSide);

        if (request.type().name().equals("LIMIT") && request.price() != null) {
            q.put("price", request.price());
            q.put("timeInForce", request.timeInForce() != null ? request.timeInForce().name() : "GTC");
        }
        if (request.closePosition()) {
            // closePosition is incompatible with quantity/reduceOnly (Binance -1106) — the caller already
            // does not set them for such legs, but we hedge on ordering: quantity
            // is always set, so we remove it here.
            q.remove("quantity");
            q.put("closePosition", "true");
        } else if (request.reduceOnly()) {
            q.put("reduceOnly", "true");
        }
        if (request.stopPrice() != null) {
            q.put("stopPrice", request.stopPrice());
        }
        if (request.workingType() != null) {
            q.put("workingType", request.workingType());
        }
        if (request.newClientOrderId() != null) {
            q.put("newClientOrderId", BinanceOrderMapper.clampClientOrderId(request.newClientOrderId()));
        }
        return q;
    }

    /** Cancel all open orders of a symbol (including conditional TP/SL legs). */
    @Override
    public Map<String, Object> cancelAllOpenOrders(String symbol) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("symbol", symbol);
        return parseJsonToMap(executeSigned("DELETE", "/fapi/v1/allOpenOrders", q, true));
    }

    /** Set initial leverage for a symbol (One-Way Mode; idempotent on the same value). */
    @Override
    public Map<String, Object> setLeverage(String symbol, int leverage) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("symbol", symbol);
        q.put("leverage", String.valueOf(leverage));
        return parseJsonToMap(executeSigned("POST", "/fapi/v1/leverage", q, true));
    }

    /** Cancel an order by orderId and/or origClientOrderId. */
    public Map<String, Object> cancelOrder(String symbol, String orderId, String clientOrderId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("symbol", symbol);
        if (orderId != null) {
            q.put("orderId", orderId);
        }
        if (clientOrderId != null) {
            q.put("origClientOrderId", BinanceOrderMapper.clampClientOrderId(clientOrderId));
        }
        return parseJsonToMap(deleteSigned("/fapi/v1/order", q));
    }

    /** Query order details by orderId and/or origClientOrderId. */
    public Map<String, Object> getOrder(String symbol, String orderId, String clientOrderId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("symbol", symbol);
        if (orderId != null) {
            q.put("orderId", orderId);
        }
        if (clientOrderId != null) {
            q.put("origClientOrderId", BinanceOrderMapper.clampClientOrderId(clientOrderId));
        }
        return parseJsonToMap(getSigned("/fapi/v1/order", q));
    }

    // ---- Account / market endpoints ----

    /** Current positions ({@code /fapi/v2/positionRisk}); array wrapped under {@code "data"}. */
    public Map<String, Object> getPositions(String symbol) {
        Map<String, String> q = new LinkedHashMap<>();
        if (symbol != null && !symbol.isBlank()) {
            q.put("symbol", symbol);
        }
        return parseJsonToMap(getSigned("/fapi/v2/positionRisk", q));
    }

    /** Account balances ({@code /fapi/v2/balance}); array wrapped under {@code "data"}. */
    public Map<String, Object> getBalance() {
        return parseJsonToMap(getSigned("/fapi/v2/balance", Collections.emptyMap()));
    }

    /** Latest price ({@code /fapi/v1/ticker/price}, public/unsigned). */
    public Map<String, Object> getPrice(String symbol) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("symbol", symbol);
        String url = props.getBaseUrl() + "/fapi/v1/ticker/price?symbol="
                + BinanceRequestSigner.urlEncodeValue(symbol);
        try {
            return parseJsonToMap(sendHttp("GET", url, false));
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException ioe) {
            throw new ExchangeException(new ExchangeError("IO_ERROR", ioe.getMessage()), ioe);
        }
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
     * @param nonIdempotent when true (a place order), a read timeout — the request may have reached
     *                      the exchange — is reported as {@code IO_ERROR_AMBIGUOUS} (never retried by
     *                      the gateway); connect-phase failures (nothing sent) stay retryable
     *                      {@code IO_ERROR}.
     */
    private String executeSigned(String method, String path, Map<String, String> query, boolean nonIdempotent) {
        syncTimeIfNeeded();
        try {
            return sendSigned(method, path, query, nonIdempotent);
        } catch (ExchangeException e) {
            // Clock drift (-1021): re-sync the server-time offset once and retry (the order was
            // rejected on timestamp, so nothing was created — safe to retry even a place).
            if (e.getError() != null && ERR_TIMESTAMP_AHEAD.equals(e.getError().code())) {
                log.warn("Binance -1021 (clock drift) — re-syncing server time and retrying once");
                syncTime();
                return sendSigned(method, path, query, nonIdempotent);
            }
            throw e;
        }
    }

    private String sendSigned(String method, String path, Map<String, String> query, boolean nonIdempotent) {
        try {
            long timestamp = System.currentTimeMillis() + timeOffsetMs;
            String fullQuery = BinanceRequestSigner.signedQuery(
                    query, props.getApiSecret(), props.getRecvWindow(), timestamp);
            String urlStr = props.getBaseUrl() + path + "?" + fullQuery;
            return sendHttp(method, urlStr, true);
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException ioe) {
            String code = (nonIdempotent && !isConnectPhaseFailure(ioe)) ? "IO_ERROR_AMBIGUOUS" : "IO_ERROR";
            throw new ExchangeException(new ExchangeError(code, ioe.getMessage()), ioe);
        } catch (Exception e) {
            throw new ExchangeException(new ExchangeError("BINANCE_HTTP_ERROR", e.getMessage()), e);
        }
    }

    /** True when the failure happened before anything was sent (safe to retry even a place). */
    static boolean isConnectPhaseFailure(IOException ioe) {
        if (ioe instanceof java.net.ConnectException || ioe instanceof java.net.UnknownHostException
                || ioe instanceof java.net.NoRouteToHostException) {
            return true;
        }
        String msg = ioe.getMessage();
        return msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("connect timed out");
    }

    /** Sync the server-time offset once (lazy, before the first signed request). */
    private void syncTimeIfNeeded() {
        if (!timeSynced) {
            syncTime();
        }
    }

    /** Fetch {@code /fapi/v1/time} (unsigned) and record {@code serverTime - localTime}. Best-effort. */
    private synchronized void syncTime() {
        try {
            long before = System.currentTimeMillis();
            String body = sendHttp("GET", props.getBaseUrl() + "/fapi/v1/time", false);
            long after = System.currentTimeMillis();
            Map<String, Object> resp = parseJsonToMap(body);
            BigDecimal serverTime = BinanceOrderMapper.extractDecimal(resp, "serverTime");
            if (serverTime != null) {
                // Compensate for round-trip: compare server time to the local midpoint.
                long localMid = before + (after - before) / 2;
                timeOffsetMs = serverTime.longValue() - localMid;
                timeSynced = true;
                log.info("Binance time sync: offsetMs={}", timeOffsetMs);
            }
        } catch (RuntimeException | IOException e) {
            log.warn("Binance time sync failed ({}): proceeding with offset={}", e.getMessage(), timeOffsetMs);
        }
    }

    // ---- Low-level HttpURLConnection helper ----

    private String sendHttp(String method, String urlStr, boolean signed) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            log.info("Binance request: {} {}", method, redactSignature(urlStr));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(props.getConnectTimeoutMs());
            conn.setReadTimeout(props.getReadTimeoutMs());

            if (signed) {
                conn.setRequestProperty("X-MBX-APIKEY", nullToEmpty(props.getApiKey()));
            }

            if ("POST".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method)) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                // Body empty: all parameters travel in the query string.
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
                    log.info("Binance response: {}", body);
                }
            }

            if (status < 200 || status >= 300) {
                throw toExchangeException(status, body, "HTTP " + status + " in Binance call");
            }
            return body == null ? "" : body;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---- Helpers ----

    /**
     * Parse a JSON body. Objects map directly; a top-level array is wrapped as {@code {"data":[...]}}
     * so array endpoints ({@code positionRisk}, {@code balance}) share the {@code Map} return type.
     */
    private Map<String, Object> parseJsonToMap(String body) {
        if (body == null || body.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            String trimmed = body.stripLeading();
            if (trimmed.startsWith("[")) {
                List<Object> list = mapper.readValue(body, new TypeReference<List<Object>>() {});
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put("data", list);
                return wrapped;
            }
            return mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            throw new ExchangeException(new ExchangeError("BINANCE_PARSE_ERROR", e.getMessage()), e);
        }
    }

    private ExchangeException toExchangeException(int status, String body, String fallbackMsg) {
        String code = String.valueOf(status);
        String message = fallbackMsg;
        try {
            if (body != null && !body.isBlank() && body.stripLeading().startsWith("{")) {
                Map<String, Object> root = mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
                Object c = root.get("code");
                if (c != null) {
                    code = String.valueOf(c);
                }
                Object m = (root.get("msg") != null ? root.get("msg") : root.get("message"));
                if (m != null) {
                    message = String.valueOf(m);
                }
            }
        } catch (Exception ignore) {
            // keep fallback code/message
        }
        return new ExchangeException(new ExchangeError(code, message));
    }

    /** Hide the HMAC signature when logging URLs. */
    private static String redactSignature(String url) {
        return url.replaceAll("signature=[0-9a-fA-F]+", "signature=***");
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
