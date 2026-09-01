package com.sonar.trading.exchange.bybit.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonar.trading.exchange.bybit.BybitFuturesClient;
import com.sonar.trading.exchange.bybit.BybitFuturesProperties;
import com.sonar.trading.exchange.bybit.BybitOrderMapper;
import com.sonar.trading.exchange.bybit.BybitPlaceOrderRequest;
import com.sonar.trading.exchange.bybit.BybitRequestSigner;
import com.sonar.trading.exchange.dto.ExchangeError;
import com.sonar.trading.exchange.exception.ExchangeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bybit V5 USDT-perpetual ({@code category=linear}) signed HTTP client. Testnet domain by
 * default (grilled: same principle as BingX's VST demo). Secrets from .env (ADR-0021).
 *
 * <p>Signing (timestamp+apiKey+recvWindow+payload, header-carried) lives in
 * {@link BybitRequestSigner} — unlike BingX, the signature never touches the URL. Endpoint
 * methods return the raw decoded JSON as {@code Map<String,Object>}; mapping into the platform's
 * {@code AdapterOrderResult} happens in {@code BybitOrderMapper} / {@code BybitExecutionAdapter}.
 *
 * <p>Uses plain {@link HttpURLConnection} (no third-party HTTP client dependency), matching
 * {@code BingxFuturesHttpService}. Not a Spring bean by itself — wired conditionally for
 * {@code trading.exchange=LIVE} via {@code BybitClientConfig}.
 */
public class BybitFuturesHttpService implements BybitFuturesClient {

    private static final Logger log = LoggerFactory.getLogger(BybitFuturesHttpService.class);
    private static final String CATEGORY = "linear";

    private final BybitFuturesProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public BybitFuturesHttpService(BybitFuturesProperties props) {
        this.props = props;
    }

    // ---- Trade endpoints ----

    /**
     * Non-idempotent: a read timeout here is ambiguous (the exchange may have accepted the
     * order) and is surfaced as {@code IO_ERROR_AMBIGUOUS}, which the gateway never retries —
     * same contract as BingX's placeOrder (Bybit does not document orderLinkId dedup either).
     */
    public Map<String, Object> placeOrder(BybitPlaceOrderRequest request) {
        return parseJsonToMap(executeSignedJsonPost("/v5/order/create", buildPlaceOrderBody(request), true));
    }

    static Map<String, Object> buildPlaceOrderBody(BybitPlaceOrderRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", CATEGORY);
        body.put("symbol", request.symbol());
        body.put("side", request.side().name());
        body.put("orderType", request.type().name());
        body.put("qty", request.qty());
        if (request.price() != null) {
            body.put("price", request.price());
        }
        if (request.timeInForce() != null) {
            // Bybit's PostOnly is a timeInForce value (not a separate flag), same slot as GTC/IOC/FOK.
            body.put("timeInForce", request.timeInForce().name());
        }
        if (request.clientOrderId() != null) {
            body.put("orderLinkId", request.clientOrderId());
        }
        body.put("positionIdx", request.positionIdx());
        if (request.reduceOnly()) {
            body.put("reduceOnly", true);
        }
        if (request.takeProfitPrice() != null) {
            body.put("takeProfit", plain(request.takeProfitPrice()));
            if (request.tpSlTriggerBy() != null) {
                body.put("tpTriggerBy", request.tpSlTriggerBy().name());
            }
        }
        if (request.stopLossPrice() != null) {
            body.put("stopLoss", plain(request.stopLossPrice()));
            if (request.tpSlTriggerBy() != null) {
                body.put("slTriggerBy", request.tpSlTriggerBy().name());
            }
        }
        if (request.triggerPrice() != null) {
            body.put("triggerPrice", request.triggerPrice());
            if (request.triggerBy() != null) {
                body.put("triggerBy", request.triggerBy().name());
            }
            if (request.triggerDirection() != null) {
                body.put("triggerDirection", request.triggerDirection());
            }
        }
        return body;
    }

    /** Cancel an order by exchange orderId (client order id not required once orderId is known). */
    public Map<String, Object> cancelOrder(String symbol, String orderId, String clientOrderId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", CATEGORY);
        body.put("symbol", symbol);
        if (orderId != null) {
            body.put("orderId", orderId);
        }
        if (clientOrderId != null) {
            body.put("orderLinkId", clientOrderId);
        }
        return parseJsonToMap(executeSignedJsonPost("/v5/order/cancel", body, false));
    }

    /** Query order details by orderId and/or clientOrderId (open + recently closed orders). */
    public Map<String, Object> getOrder(String symbol, String orderId, String clientOrderId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("category", CATEGORY);
        q.put("symbol", symbol);
        if (orderId != null) {
            q.put("orderId", orderId);
        }
        if (clientOrderId != null) {
            q.put("orderLinkId", clientOrderId);
        }
        return parseJsonToMap(executeSignedGet("/v5/order/realtime", q));
    }

    /**
     * Bybit V5 has no bulk close-all endpoint — synthesized (grilled Q3): read positions, fire a
     * reduce-only MARKET close per non-flat one, then re-read and verify. Any symbol still
     * non-flat after the closes is surfaced via {@code ExchangeException} rather than silently
     * trusted — the caller (StopGuard/PositionOpsService path) sees it as a failed operation.
     */
    public Map<String, Object> closeAllPositions(String symbol) {
        List<Map<String, Object>> before = BybitOrderMapper.resultList(getPositions(symbol));
        List<String> closedSymbols = new ArrayList<>();
        for (Map<String, Object> row : before) {
            BigDecimal size = BybitOrderMapper.extractDecimal(row, "size");
            if (size == null || size.signum() == 0) {
                continue;
            }
            String rowSymbol = BybitOrderMapper.extractString(row, "symbol");
            String side = BybitOrderMapper.extractString(row, "side"); // "Buy" (long) or "Sell" (short)
            com.sonar.trading.exchange.bybit.BybitOrderSide closeSide =
                    "Buy".equalsIgnoreCase(side) ? com.sonar.trading.exchange.bybit.BybitOrderSide.Sell
                            : com.sonar.trading.exchange.bybit.BybitOrderSide.Buy;
            BybitPlaceOrderRequest closeReq = new BybitPlaceOrderRequest(rowSymbol, closeSide,
                    com.sonar.trading.exchange.bybit.BybitOrderType.Market,
                    size.stripTrailingZeros().toPlainString(), null, null,
                    "ca-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                    null, null, null, true);
            try {
                placeOrder(closeReq);
                closedSymbols.add(rowSymbol);
            } catch (RuntimeException e) {
                log.error("[BYBIT] close-all: reduce-only close failed for {}: {}", rowSymbol, e.toString());
            }
        }

        // Bybit doesn't guarantee /v5/position/list reflects a fill the instant the close order
        // acks — a verification read immediately after can still see the pre-close size. Give it
        // a few short beats before declaring the close incomplete (still bounded: ~1.5s worst case).
        List<String> stillOpen = List.of();
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                sleep(500);
            }
            List<Map<String, Object>> after = BybitOrderMapper.resultList(getPositions(symbol));
            stillOpen = after.stream()
                    .filter(row -> {
                        BigDecimal size = BybitOrderMapper.extractDecimal(row, "size");
                        return size != null && size.signum() != 0;
                    })
                    .map(row -> BybitOrderMapper.extractString(row, "symbol"))
                    .toList();
            if (stillOpen.isEmpty()) {
                break;
            }
        }

        if (!stillOpen.isEmpty()) {
            throw new ExchangeException(new ExchangeError("CLOSE_ALL_INCOMPLETE",
                    "close-all sent for " + closedSymbols + " but still open after verification: " + stillOpen));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("closedSymbols", closedSymbols);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retCode", 0);
        out.put("result", result);
        return out;
    }

    // ---- Account / market endpoints ----

    /** All open orders for a symbol, including untriggered conditional TP/SL orders. */
    public Map<String, Object> getOpenOrders(String symbol) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("category", CATEGORY);
        if (symbol != null && !symbol.isBlank()) {
            q.put("symbol", symbol);
        }
        q.put("openOnly", "0");
        return parseJsonToMap(executeSignedGet("/v5/order/realtime", q));
    }

    /** Current positions (optionally scoped to a symbol). */
    public Map<String, Object> getPositions(String symbol) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("category", CATEGORY);
        if (symbol != null && !symbol.isBlank()) {
            q.put("symbol", symbol);
        } else {
            // Bybit V5 requires either symbol or settleCoin for a linear position list.
            q.put("settleCoin", "USDT");
        }
        return parseJsonToMap(executeSignedGet("/v5/position/list", q));
    }

    /** Unified account wallet balance (USDT). */
    public Map<String, Object> getBalance() {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("accountType", "UNIFIED");
        q.put("coin", "USDT");
        return parseJsonToMap(executeSignedGet("/v5/account/wallet-balance", q));
    }

    // Bybit caps page size per-endpoint; a caller-requested limit above these gets rejected
    // outright (retCode=10001 params error), not clamped server-side.
    private static final int CLOSED_PNL_MAX_LIMIT = 100;
    private static final int TRANSACTION_LOG_MAX_LIMIT = 50;
    private static final int MAX_INCOME_PAGES = 10; // bounds worst-case: 1000 / 500 rows per source

    /**
     * Merges {@code /v5/position/closed-pnl} (realized PnL) and {@code /v5/account/transaction-log}
     * (funding/fees) into a single {@code income} list under {@code data}, matching BingX's
     * {@code getIncome} response shape closely enough for {@code MinutePoller.sumIncomeSince} to
     * consume unchanged. Grilled Q4: documented approximation, not a strict ledger match.
     *
     * <p>Throws when BOTH sources fail (rather than returning an empty-but-successful list) so the
     * caller ({@code BybitAccountReader.sumIncomeSince}) can tell "no income this period" (real 0)
     * apart from "couldn't ask the exchange" (must keep the previous {@code realized_pnl}, not
     * silently zero out the daily-drawdown baseline). A single source failing still degrades to
     * partial data — logged, not thrown — same as before.
     */
    public Map<String, Object> getIncome(String incomeType, Long startTime, Long endTime, Integer limit) {
        List<Map<String, Object>> merged = new ArrayList<>();
        boolean pnlFailed = false;
        boolean txFailed = false;

        Map<String, String> pnlQuery = new LinkedHashMap<>();
        pnlQuery.put("category", CATEGORY);
        if (startTime != null) {
            pnlQuery.put("startTime", String.valueOf(startTime));
        }
        if (endTime != null) {
            pnlQuery.put("endTime", String.valueOf(endTime));
        }
        pnlQuery.put("limit", String.valueOf(clampLimit(limit, CLOSED_PNL_MAX_LIMIT)));
        try {
            for (Map<String, Object> row : fetchPaginated("/v5/position/closed-pnl", pnlQuery, MAX_INCOME_PAGES)) {
                BigDecimal pnl = BybitOrderMapper.extractDecimal(row, "closedPnl");
                if (pnl != null) {
                    merged.add(Map.of("income", pnl.toPlainString(), "incomeType", "REALIZED_PNL"));
                }
            }
        } catch (RuntimeException e) {
            pnlFailed = true;
            log.warn("[BYBIT] getIncome: closed-pnl fetch failed: {}", e.toString());
        }

        Map<String, String> txQuery = new LinkedHashMap<>();
        txQuery.put("accountType", "UNIFIED");
        txQuery.put("category", CATEGORY);
        if (incomeType != null && !incomeType.isBlank()) {
            // transaction-log's own "type" filter (e.g. SETTLEMENT for funding) — closed-pnl has
            // no equivalent concept (it's realized-PnL-only), so incomeType only narrows this leg.
            txQuery.put("type", incomeType);
        }
        if (startTime != null) {
            txQuery.put("startTime", String.valueOf(startTime));
        }
        if (endTime != null) {
            txQuery.put("endTime", String.valueOf(endTime));
        }
        txQuery.put("limit", String.valueOf(clampLimit(limit, TRANSACTION_LOG_MAX_LIMIT)));
        try {
            for (Map<String, Object> row : fetchPaginated("/v5/account/transaction-log", txQuery, MAX_INCOME_PAGES)) {
                BigDecimal change = BybitOrderMapper.extractDecimal(row, "change");
                String type = BybitOrderMapper.extractString(row, "type");
                if (change != null && !"TRADE".equalsIgnoreCase(type)) {
                    // TRADE rows double-count what closed-pnl already reported; funding/fees/settlement don't.
                    merged.add(Map.of("income", change.toPlainString(), "incomeType",
                            type != null ? type.toUpperCase(Locale.ROOT) : "OTHER"));
                }
            }
        } catch (RuntimeException e) {
            txFailed = true;
            log.warn("[BYBIT] getIncome: transaction-log fetch failed: {}", e.toString());
        }

        if (pnlFailed && txFailed) {
            throw new ExchangeException(new ExchangeError("BYBIT_INCOME_UNAVAILABLE",
                    "both closed-pnl and transaction-log fetches failed — realized PnL for this period is unknown"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retCode", 0);
        out.put("data", merged);
        return out;
    }

    private static int clampLimit(Integer requested, int max) {
        if (requested == null) {
            return max;
        }
        return Math.min(requested, max);
    }

    /**
     * Follows Bybit's {@code result.nextPageCursor} across pages, bounded by {@code maxPages} —
     * a day's worth of activity can exceed one page (especially transaction-log's 50-row cap), and
     * silently reading only page one understates realized PnL for the risk gate that consumes it.
     */
    private List<Map<String, Object>> fetchPaginated(String path, Map<String, String> baseQuery, int maxPages) {
        List<Map<String, Object>> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < maxPages; page++) {
            Map<String, String> q = new LinkedHashMap<>(baseQuery);
            if (cursor != null && !cursor.isBlank()) {
                q.put("cursor", cursor);
            }
            Map<String, Object> resp = parseJsonToMap(executeSignedGet(path, q));
            all.addAll(BybitOrderMapper.resultList(resp));
            cursor = BybitOrderMapper.extractString(resp, "nextPageCursor", "result.nextPageCursor");
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        return all;
    }

    /** Latest ticker for a symbol (public endpoint, unsigned). */
    public Map<String, Object> getPrice(String symbol) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("category", CATEGORY);
        q.put("symbol", symbol);
        return parseJsonToMap(sendHttp("GET", props.getBaseUrl() + "/v5/market/tickers?" + encodeQuery(q), false, null));
    }

    // ---- Signed HTTP helpers ----

    private String executeSignedGet(String path, Map<String, String> query) {
        return executeSigned("GET", path, buildQueryString(query), null, false);
    }

    private String executeSignedJsonPost(String path, Map<String, Object> body, boolean nonIdempotent) {
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new ExchangeException(new ExchangeError("BYBIT_SERIALIZE_ERROR", e.getMessage()), e);
        }
        return executeSigned("POST", path, null, json, nonIdempotent);
    }

    /**
     * @param nonIdempotent when true, a read timeout (request may have reached the exchange) is
     *                      reported as {@code IO_ERROR_AMBIGUOUS} — never retried by the gateway;
     *                      connect-phase failures (nothing sent) stay retryable {@code IO_ERROR}.
     */
    private String executeSigned(String method, String path, String queryString, String jsonBody,
                                  boolean nonIdempotent) {
        try {
            long timestamp = System.currentTimeMillis();
            String payload = "GET".equals(method) ? (queryString == null ? "" : queryString) : jsonBody;
            String signature = BybitRequestSigner.sign(payload, props.getApiSecret(), props.getApiKey(),
                    props.getRecvWindow(), timestamp);
            String url = props.getBaseUrl() + path + ("GET".equals(method) && queryString != null && !queryString.isBlank()
                    ? "?" + queryString : "");
            return sendHttp(method, url, true, new SignedHeaders(timestamp, signature), jsonBody);
        } catch (ExchangeException e) {
            // sendHttp already wraps any IOException as IO_ERROR; a non-idempotent place whose
            // failure happened past the connect phase is ambiguous (may have reached the exchange)
            // and must not be blindly retried by the gateway.
            if (nonIdempotent && e.getError() != null && "IO_ERROR".equals(e.getError().code())
                    && e.getCause() instanceof IOException ioe && !isConnectPhaseFailure(ioe)) {
                throw new ExchangeException(new ExchangeError("IO_ERROR_AMBIGUOUS", ioe.getMessage()), ioe);
            }
            throw e;
        } catch (Exception e) {
            throw new ExchangeException(new ExchangeError("BYBIT_HTTP_ERROR", e.getMessage()), e);
        }
    }

    private record SignedHeaders(long timestamp, String signature) {}

    /** True when the failure happened before anything was sent (safe to retry even a place). */
    private static boolean isConnectPhaseFailure(IOException ioe) {
        if (ioe instanceof java.net.ConnectException || ioe instanceof java.net.UnknownHostException
                || ioe instanceof java.net.NoRouteToHostException) {
            return true;
        }
        String msg = ioe.getMessage();
        return msg != null && msg.toLowerCase(Locale.ROOT).contains("connect timed out");
    }

    // ---- Low-level HttpURLConnection helpers ----

    private String sendHttp(String method, String urlStr, boolean signed, SignedHeaders headers) {
        return sendHttp(method, urlStr, signed, headers, null);
    }

    private String sendHttp(String method, String urlStr, boolean signed, SignedHeaders headers, String jsonBody) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            log.debug("Bybit request: {} {}", method, urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(props.getConnectTimeoutMs());
            conn.setReadTimeout(props.getReadTimeoutMs());
            conn.setRequestProperty("Content-Type", "application/json");

            if (signed && headers != null) {
                conn.setRequestProperty("X-BAPI-API-KEY", nullToEmpty(props.getApiKey()));
                conn.setRequestProperty("X-BAPI-TIMESTAMP", String.valueOf(headers.timestamp()));
                conn.setRequestProperty("X-BAPI-RECV-WINDOW", String.valueOf(props.getRecvWindow()));
                conn.setRequestProperty("X-BAPI-SIGN", headers.signature());
            }

            if ("POST".equalsIgnoreCase(method)) {
                conn.setDoOutput(true);
                byte[] payload = (jsonBody == null ? "" : jsonBody).getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = null;
            if (is != null) {
                try (is) {
                    body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    log.debug("Bybit response: {}", body);
                }
            }

            if (status < 200 || status >= 300) {
                throw toExchangeException(status, body, "HTTP " + status + " in Bybit call");
            }
            return body == null ? "" : body;
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException ioe) {
            throw new ExchangeException(new ExchangeError("IO_ERROR", ioe.getMessage()), ioe);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // ---- Helpers ----

    private static String buildQueryString(Map<String, String> params) {
        return encodeQuery(params);
    }

    private static String encodeQuery(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            first = false;
            String value = entry.getValue() == null ? "" : entry.getValue();
            sb.append(entry.getKey()).append("=").append(urlEncodeValue(value));
        }
        return sb.toString();
    }

    private static String urlEncodeValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private Map<String, Object> parseJsonToMap(String body) {
        if (body == null || body.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return requireOk(mapper.readValue(body, new TypeReference<Map<String, Object>>() {}));
        } catch (IOException e) {
            throw new ExchangeException(new ExchangeError("BYBIT_PARSE_ERROR", e.getMessage()), e);
        }
    }

    /**
     * Bybit V5 reports business rejections as HTTP 200 with a non-zero {@code retCode} — the same
     * "200 + code!=0" gotcha as BingX. Without this check a rejected order parses to a status-less
     * map, maps to state NEW and becomes an eternal zombie reconciliation can never resolve.
     */
    static Map<String, Object> requireOk(Map<String, Object> root) {
        Object code = root.get("retCode");
        if (code == null) {
            return root;
        }
        String c = String.valueOf(code);
        if ("0".equals(c)) {
            return root;
        }
        Object m = root.get("retMsg");
        throw new ExchangeException(new ExchangeError(c,
                m != null ? String.valueOf(m) : "Bybit business error retCode=" + c));
    }

    private ExchangeException toExchangeException(int status, String body, String fallbackMsg) {
        String code = String.valueOf(status);
        String message = fallbackMsg;
        try {
            if (body != null && !body.isBlank()) {
                Map<String, Object> root = mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
                Object c = root.get("retCode");
                if (c != null) {
                    code = String.valueOf(c);
                }
                Object m = root.get("retMsg");
                if (m != null) {
                    message = String.valueOf(m);
                }
            }
        } catch (Exception ignore) {
            // keep fallback code/message
        }
        return new ExchangeException(new ExchangeError(code, message));
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
