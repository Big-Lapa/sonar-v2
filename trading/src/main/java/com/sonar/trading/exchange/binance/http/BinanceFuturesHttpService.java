package com.sonar.trading.exchange.binance.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Binance USDT-M Futures signed HTTP client (mirrors {@code BybitFuturesHttpService} /
 * {@code BingxFuturesHttpService}). Testnet domain by default; secrets from .env (ADR-0021).
 *
 * <p>Signing lives in {@link BinanceRequestSigner} (single query-string HMAC, signature appended
 * to the same query string that is sent). Endpoint methods return the decoded JSON as
 * {@code Map<String,Object>}; a top-level JSON array response is wrapped under a {@code "data"}
 * key so the return type stays uniform — see {@link BinanceOrderMapper#dataRows}.
 *
 * <p>Uses plain {@link HttpURLConnection} (no third-party HTTP client dependency). Not a Spring
 * bean by itself — wired per account by {@code AccountWiring} from that account's own credentials.
 */
public class BinanceFuturesHttpService implements BinanceFuturesClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceFuturesHttpService.class);

    /** Binance rejects a signed request whose timestamp is outside recvWindow of server time. */
    private static final String ERR_TIMESTAMP_AHEAD = "-1021";

    private final BinanceFuturesProperties props;

    /** serverTime - localTime, applied to every signed request's timestamp. Synced lazily. */
    private volatile long timeOffsetMs = 0L;
    private volatile boolean timeSynced = false;

    private final ObjectMapper mapper = new ObjectMapper();

    public BinanceFuturesHttpService(BinanceFuturesProperties props) {
        this.props = props;
    }

    // ---- Trade endpoints ----

    /**
     * Non-idempotent: a read timeout here is ambiguous (the order may have reached the exchange)
     * and is surfaced as {@code IO_ERROR_AMBIGUOUS}, which the gateway never retries — a blind
     * retry with the same {@code newClientOrderId} could double the position. Connect-phase
     * failures (nothing sent) stay retryable {@code IO_ERROR}.
     */
    public Map<String, Object> placeOrder(BinancePlaceOrderRequest request) {
        return parseJsonToMap(executeSigned("POST", "/fapi/v1/order", buildPlaceOrderQuery(request), true));
    }

    /**
     * Assemble the signed query parameters for a place-order call. Package-private for unit testing.
     *
     * <p>{@code positionSide} defaults to {@code BOTH} (One-Way Mode). {@code price}/
     * {@code timeInForce} are sent only for LIMIT. {@code closePosition} and {@code quantity}/
     * {@code reduceOnly} are mutually exclusive on Binance (error -1106) — a full-close conditional
     * order never carries a quantity.
     */
    static Map<String, String> buildPlaceOrderQuery(BinancePlaceOrderRequest request) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("symbol", request.symbol());
        q.put("side", request.side().name());
        q.put("type", request.type().name());

        String positionSide = request.positionSide() != null ? request.positionSide().name() : "BOTH";
        q.put("positionSide", positionSide);

        if (request.type().name().equals("LIMIT") && request.price() != null) {
            q.put("price", request.price());
            q.put("timeInForce", request.timeInForce() != null ? request.timeInForce().name() : "GTC");
        }
        if (request.closePosition()) {
            q.put("closePosition", "true");
        } else {
            if (request.quantity() == null) {
                // A null quantity here would go out as the query-string literal "quantity=" —
                // BinanceRequestSigner.buildQueryStringEncoded turns a null value into an empty
                // string rather than dropping the key — and come back as an opaque -1102 from the
                // exchange instead of failing at the call site that forgot to compute a size.
                throw new IllegalArgumentException(
                        "BinancePlaceOrderRequest.quantity is required unless closePosition=true");
            }
            q.put("quantity", request.quantity());
            if (request.reduceOnly()) {
                q.put("reduceOnly", "true");
            }
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

    /** Cancel ALL open orders of a symbol (leftover conditional TP/SL legs). */
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
        return parseJsonToMap(executeSigned("DELETE", "/fapi/v1/order", q, false));
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
        return parseJsonToMap(executeSigned("GET", "/fapi/v1/order", q, false));
    }

    /** All open orders for a symbol, including untriggered STOP_MARKET/TAKE_PROFIT_MARKET legs. */
    @Override
    public Map<String, Object> getOpenOrders(String symbol) {
        Map<String, String> q = new LinkedHashMap<>();
        if (symbol != null && !symbol.isBlank()) {
            q.put("symbol", symbol);
        }
        return parseJsonToMap(executeSigned("GET", "/fapi/v1/openOrders", q, false));
    }

    // ---- Account / market endpoints ----

    /** Current positions ({@code /fapi/v2/positionRisk}); array wrapped under {@code "data"}. */
    public Map<String, Object> getPositions(String symbol) {
        Map<String, String> q = new LinkedHashMap<>();
        if (symbol != null && !symbol.isBlank()) {
            q.put("symbol", symbol);
        }
        return parseJsonToMap(executeSigned("GET", "/fapi/v2/positionRisk", q, false));
    }

    /** Account balances ({@code /fapi/v2/balance}); array wrapped under {@code "data"}. */
    public Map<String, Object> getBalance() {
        return parseJsonToMap(executeSigned("GET", "/fapi/v2/balance", Collections.emptyMap(), false));
    }

    /** Latest traded price ({@code /fapi/v1/ticker/price}, public/unsigned). */
    public Map<String, Object> getPrice(String symbol) {
        String url = props.getBaseUrl() + "/fapi/v1/ticker/price?symbol="
                + BinanceRequestSigner.urlEncodeValue(symbol);
        return parseJsonToMap(sendHttp("GET", url, false, null));
    }

    /** Current mark price ({@code /fapi/v1/premiumIndex}, public/unsigned) — see interface doc. */
    @Override
    public Map<String, Object> getMarkPrice(String symbol) {
        String url = props.getBaseUrl() + "/fapi/v1/premiumIndex?symbol="
                + BinanceRequestSigner.urlEncodeValue(symbol);
        return parseJsonToMap(sendHttp("GET", url, false, null));
    }

    /** Binance's hard per-call cap on {@code /fapi/v1/income}. */
    private static final int INCOME_PAGE_LIMIT = 1000;
    /** Bounds worst-case pagination (a day with more than 20k income rows is not realistic). */
    private static final int MAX_INCOME_PAGES = 20;

    /**
     * Realized PnL + funding/fees ledger ({@code /fapi/v1/income}); merged array wrapped under
     * {@code "data"}. Paginates by advancing {@code startTime} to the last row's own timestamp
     * whenever a page comes back full (unlike Bybit's cursor, this endpoint has no cursor — but
     * time-based paging works the same way, provided rows already seen get filtered back out by
     * {@code tranId} rather than skipped past — a {@code +1} cursor would drop any other row
     * sharing that exact millisecond, which funding settlement across many symbols routinely
     * does) — a single 1000-row call silently truncated any day with more funding intervals +
     * multi-symbol fees than that, understating realized PnL to whatever the drawdown gate reads.
     *
     * <p>A business-error response (HTTP 200, non-array body) on ANY page — not just the first —
     * is returned as-is without merging: the caller ({@code BinanceAccountReader.sumIncomeSince})
     * distinguishes that from a genuinely empty page and returns {@code null} rather than a
     * partial sum that would look like a smaller-but-real answer. The same non-array shape is
     * returned when pagination can't complete cleanly (page budget exhausted while still full,
     * or a full page added no new rows) — reported as unavailable rather than silently handing
     * back an understated sum, exactly the failure mode this pagination exists to prevent.
     */
    @Override
    public Map<String, Object> getIncome(String incomeType, Long startTime, Long endTime, Integer limit) {
        int pageLimit = limit != null ? Math.min(limit, INCOME_PAGE_LIMIT) : INCOME_PAGE_LIMIT;
        List<Object> merged = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        Long cursorStart = startTime;

        for (int page = 0; ; page++) {
            if (page >= MAX_INCOME_PAGES) {
                return truncated(merged.size(), "page budget (" + MAX_INCOME_PAGES + ") exhausted while pages "
                        + "were still coming back full");
            }

            Map<String, String> q = new LinkedHashMap<>();
            if (incomeType != null && !incomeType.isBlank()) {
                q.put("incomeType", incomeType);
            }
            if (cursorStart != null) {
                q.put("startTime", String.valueOf(cursorStart));
            }
            if (endTime != null) {
                q.put("endTime", String.valueOf(endTime));
            }
            q.put("limit", String.valueOf(pageLimit));

            Map<String, Object> resp = parseJsonToMap(executeSigned("GET", "/fapi/v1/income", q, false));
            Object data = resp.get("data");
            if (!(data instanceof List<?> rows)) {
                return resp;
            }
            if (rows.isEmpty()) {
                break; // clean completion: nothing more in range
            }

            int newRows = 0;
            for (Object row : rows) {
                if (seenKeys.add(dedupKey(row))) {
                    merged.add(row);
                    newRows++;
                }
            }
            if (rows.size() < pageLimit) {
                break; // clean completion: short page, no more data past this point
            }
            if (newRows == 0) {
                // A full page that added nothing new: more rows share the last timestamp than fit
                // in one page. Advancing further would just refetch the same window forever.
                return truncated(merged.size(), "a full page added no new rows (more income at one "
                        + "timestamp than the page limit)");
            }

            Long lastTime = lastRowTime(rows.get(rows.size() - 1));
            if (lastTime == null) {
                return truncated(merged.size(), "last row carried no parseable time to page from");
            }
            cursorStart = lastTime;
        }
        return wrapData(merged);
    }

    private Map<String, Object> truncated(int rowsSoFar, String reason) {
        log.warn("[BINANCE] getIncome pagination could not complete cleanly ({} rows merged so far): {} — "
                + "reporting as unavailable rather than an understated sum", rowsSoFar, reason);
        return Map.of("code", -1, "msg", "getIncome pagination truncated: " + reason);
    }

    /**
     * Dedup key for one income row across pages. Prefers {@code tranId} (unique per Binance's own
     * ledger); a row without one falls back to a composite of its own fields rather than being
     * added unconditionally. The fallback matters BECAUSE the cursor deliberately re-fetches the
     * last page's exact millisecond ({@code cursorStart = lastTime}, no {@code +1}) so no row at
     * that boundary is skipped — that overlap is by design, so a {@code tranId}-less row would
     * otherwise be counted from both pages and double its income into the sum (the wrong direction
     * for a drawdown gate: it makes the gate look TOO permissive, not too strict, hiding the
     * failure rather than tripping it).
     */
    static String dedupKey(Object row) {
        if (!(row instanceof Map<?, ?> m)) {
            return String.valueOf(row);
        }
        Object tranId = m.get("tranId");
        if (tranId != null) {
            return "tranId:" + tranId;
        }
        return "fallback:" + m.get("time") + "|" + m.get("symbol") + "|" + m.get("incomeType") + "|" + m.get("income");
    }

    private static Long lastRowTime(Object row) {
        if (!(row instanceof Map<?, ?> m)) {
            return null;
        }
        Object time = m.get("time");
        if (time == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(time));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Map<String, Object> wrapData(List<Object> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", rows);
        return out;
    }

    // ---- Signed HTTP helpers ----

    private String executeSigned(String method, String path, Map<String, String> query, boolean nonIdempotent) {
        syncTimeIfNeeded();
        try {
            return sendSigned(method, path, query, nonIdempotent);
        } catch (ExchangeException e) {
            // Clock drift: re-sync the server-time offset once and retry. The request was
            // rejected on timestamp before any order logic ran, so nothing was created — safe to
            // retry even a place.
            if (e.getError() != null && ERR_TIMESTAMP_AHEAD.equals(e.getError().code())) {
                log.warn("Binance -1021 (clock drift) — re-syncing server time and retrying once");
                syncTime();
                return sendSigned(method, path, query, nonIdempotent);
            }
            throw e;
        }
    }

    private String sendSigned(String method, String path, Map<String, String> query, boolean nonIdempotent) {
        long timestamp = System.currentTimeMillis() + timeOffsetMs;
        String fullQuery = BinanceRequestSigner.signedQuery(query, props.getApiSecret(), props.getRecvWindow(), timestamp);
        String urlStr = props.getBaseUrl() + path + "?" + fullQuery;
        return sendHttp(method, urlStr, true, nonIdempotent);
    }

    /** True when the failure happened before anything was sent (safe to retry even a place). */
    private static boolean isConnectPhaseFailure(IOException ioe) {
        if (ioe instanceof java.net.ConnectException || ioe instanceof java.net.UnknownHostException
                || ioe instanceof java.net.NoRouteToHostException) {
            return true;
        }
        String msg = ioe.getMessage();
        return msg != null && msg.toLowerCase(Locale.ROOT).contains("connect timed out");
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
            String body = sendHttp("GET", props.getBaseUrl() + "/fapi/v1/time", false, null);
            long after = System.currentTimeMillis();
            Map<String, Object> resp = parseJsonToMap(body);
            BigDecimal serverTime = BinanceOrderMapper.extractDecimal(resp, "serverTime");
            if (serverTime != null) {
                long localMid = before + (after - before) / 2;
                timeOffsetMs = serverTime.longValue() - localMid;
                timeSynced = true;
                log.info("Binance time sync: offsetMs={}", timeOffsetMs);
            }
        } catch (RuntimeException e) {
            log.warn("Binance time sync failed ({}): proceeding with offset={}", e.getMessage(), timeOffsetMs);
        }
    }

    // ---- Low-level HttpURLConnection helper ----

    /**
     * @param nonIdempotent only meaningful when {@code signed} is true: a read timeout on a
     *                      non-idempotent write (order placement/cancel) is ambiguous and surfaced
     *                      as {@code IO_ERROR_AMBIGUOUS}. Null for unsigned/idempotent calls.
     */
    private String sendHttp(String method, String urlStr, boolean signed, Boolean nonIdempotent) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            log.debug("Binance request: {} {}", method, redactSignature(urlStr));
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
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = null;
            if (is != null) {
                try (is) {
                    body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    log.debug("Binance response: {}", body);
                }
            }

            if (status < 200 || status >= 300) {
                throw toExchangeException(status, body, "HTTP " + status + " in Binance call");
            }
            return body == null ? "" : body;
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException ioe) {
            boolean ambiguous = Boolean.TRUE.equals(nonIdempotent) && !isConnectPhaseFailure(ioe);
            throw new ExchangeException(new ExchangeError(ambiguous ? "IO_ERROR_AMBIGUOUS" : "IO_ERROR", ioe.getMessage()), ioe);
        } catch (Exception e) {
            throw new ExchangeException(new ExchangeError("BINANCE_HTTP_ERROR", e.getMessage()), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---- Helpers ----

    /**
     * Parse a JSON body. Objects map directly; a top-level array is wrapped as {@code {"data":[...]}}
     * so array endpoints ({@code positionRisk}, {@code balance}, {@code openOrders}, {@code income})
     * share the {@code Map} return type — see {@link BinanceOrderMapper#dataRows}.
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
                Object m = root.get("msg") != null ? root.get("msg") : root.get("message");
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
