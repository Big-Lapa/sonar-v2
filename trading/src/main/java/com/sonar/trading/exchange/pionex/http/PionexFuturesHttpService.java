package com.sonar.trading.exchange.pionex.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonar.trading.exchange.dto.ExchangeError;
import com.sonar.trading.exchange.exception.ExchangeException;
import com.sonar.trading.exchange.pionex.PionexFuturesClient;
import com.sonar.trading.exchange.pionex.PionexFuturesProperties;
import com.sonar.trading.exchange.pionex.PionexRequestSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pionex Futures ({@code /uapi/v1}) signed HTTP client — ported from the working Sonar v1
 * implementation (sibling {@code sonar-platform} repo,
 * {@code services/slippager/pionex/PionexFuturesClient.java}), trimmed to the read-only account
 * calls this iteration needs. Signing lives in {@link PionexRequestSigner}.
 *
 * <p>Uses plain {@link HttpURLConnection} (no third-party HTTP client dependency), same as
 * {@code BingxFuturesHttpService} — not a Spring bean by itself, wired conditionally for
 * per account by {@code AccountWiring} from that account's own credentials.
 *
 * <p>Field names in {@link #requireOk} follow the v1 reference exactly ({@code result}/{@code
 * code}/{@code message} envelope, confirmed against the real API there). The balance/position
 * <em>row</em> field names in {@code PionexAccountParser}, however, were never exercised in v1
 * beyond raw logging — they're a best-effort guess pending one live call against a real account.
 */
public class PionexFuturesHttpService implements PionexFuturesClient {

    private static final Logger log = LoggerFactory.getLogger(PionexFuturesHttpService.class);

    private final PionexFuturesProperties props;

    private final ObjectMapper mapper = new ObjectMapper();

    public PionexFuturesHttpService(PionexFuturesProperties props) {
        this.props = props;
    }

    @Override
    public Map<String, Object> getBalances() {
        return parseJsonToMap(executeSigned("GET", "/uapi/v1/account/balances", Collections.emptyMap(), null));
    }

    @Override
    public Map<String, Object> getPositions(String symbol) {
        Map<String, String> q = new LinkedHashMap<>();
        if (symbol != null && !symbol.isBlank()) {
            q.put("symbol", symbol);
        }
        return parseJsonToMap(executeSigned("GET", "/uapi/v1/account/positions", q, null));
    }

    @Override
    public Map<String, Object> getBookTicker(String symbol) {
        String urlStr = props.getBaseUrl() + "/api/v1/market/bookTickers?symbol="
                + URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        try {
            String body = sendHttp("GET", urlStr, null, null);
            if (body.isBlank()) {
                return Collections.emptyMap();
            }
            // No requireOk: result:false + MARKET_INVALID_SYMBOL is a valid answer ("no such pair").
            return mapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException ioe) {
            throw new ExchangeException(new ExchangeError("IO_ERROR", ioe.getMessage()), ioe);
        } catch (Exception e) {
            throw new ExchangeException(new ExchangeError("PIONEX_HTTP_ERROR", e.getMessage()), e);
        }
    }

    // ---- Signed HTTP helpers ----

    private String executeSigned(String method, String path, Map<String, String> query, String jsonBody) {
        try {
            long ts = System.currentTimeMillis();
            String sortedQuery = PionexRequestSigner.sortedQuery(query, ts);
            String signature = PionexRequestSigner.sign(method, path, sortedQuery, jsonBody, props.getApiSecret());
            String urlStr = props.getBaseUrl() + path + "?" + sortedQuery;
            return sendHttp(method, urlStr, jsonBody, signature);
        } catch (ExchangeException e) {
            throw e;
        } catch (IOException ioe) {
            throw new ExchangeException(new ExchangeError("IO_ERROR", ioe.getMessage()), ioe);
        } catch (Exception e) {
            throw new ExchangeException(new ExchangeError("PIONEX_HTTP_ERROR", e.getMessage()), e);
        }
    }

    private String sendHttp(String method, String urlStr, String jsonBody, String signature) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            log.debug("Pionex request: {} {}", method, urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(props.getConnectTimeoutMs());
            conn.setReadTimeout(props.getReadTimeoutMs());
            if (signature != null) { // null = public market endpoint, sent without credentials
                conn.setRequestProperty("PIONEX-KEY", nullToEmpty(props.getApiKey()));
                conn.setRequestProperty("PIONEX-SIGNATURE", signature);
            }

            if (jsonBody != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.getOutputStream().write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
            String body = null;
            if (is != null) {
                try (is) {
                    body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    log.debug("Pionex response: {}", body);
                }
            }
            if (status < 200 || status >= 300) {
                throw toExchangeException(status, body, "HTTP " + status + " in Pionex call");
            }
            return body == null ? "" : body;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private Map<String, Object> parseJsonToMap(String body) {
        if (body == null || body.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return requireOk(mapper.readValue(body, new TypeReference<Map<String, Object>>() {}));
        } catch (IOException e) {
            throw new ExchangeException(new ExchangeError("PIONEX_PARSE_ERROR", e.getMessage()), e);
        }
    }

    /**
     * Pionex's flavour of the "HTTP 200 but failed" gotcha (confirmed in the v1 reference): every
     * response carries {@code result:boolean}; on failure {@code code}/{@code message} explain.
     * Same lesson as BingX {@code code!=0} — a rejected/failed call must never look like a
     * successful one.
     */
    static Map<String, Object> requireOk(Map<String, Object> root) {
        Object result = root.get("result");
        if (Boolean.TRUE.equals(result) || "true".equals(String.valueOf(result))) {
            return root;
        }
        Object code = root.get("code");
        Object msg = root.get("message") != null ? root.get("message") : root.get("msg");
        // The class javadoc treats `result` as present on every real response — its absence here
        // is therefore not "ok by default" but an envelope this client doesn't recognise (an HTML
        // error page, a proxy response, a schema change), which is exactly the shape of failure
        // that must never be read as success.
        throw new ExchangeException(new ExchangeError(
                code != null ? String.valueOf(code) : "PIONEX_UNEXPECTED_RESPONSE",
                msg != null ? String.valueOf(msg)
                        : result == null ? "Pionex response missing 'result' field: " + root
                        : "Pionex business error" + (code != null ? " code=" + code : "")));
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
                Object m = root.get("message") != null ? root.get("message") : root.get("msg");
                if (m != null) {
                    message = String.valueOf(m);
                }
            }
        } catch (Exception ignore) {
            // keep fallback code/message
        }
        return new ExchangeException(new ExchangeError(code, message));
    }
}
