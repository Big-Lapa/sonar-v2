package com.sonar.trading.exchange.pionex.http;

import com.sonar.trading.exchange.exception.ExchangeException;
import com.sonar.trading.exchange.pionex.PionexFuturesProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code requireOk} is package-private specifically so it can be unit-tested without HTTP — same
 * rationale as {@code BybitFuturesHttpService.requireOk}.
 */
class PionexFuturesHttpServiceTest {

    private ServerSocket server;

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.close();
        }
    }

    /**
     * Local stand-in for api.pionex.com: answers every request with {@code status}/{@code body} and
     * records the request URI and headers (names lower-cased) of the last one. A bare
     * {@link ServerSocket} rather than {@code com.sun.net.httpserver}, which cannot open its selector
     * on some Windows JDKs ("Unable to establish loopback connection").
     */
    private PionexFuturesHttpService serviceAnswering(int status, String body,
                                                      AtomicReference<Map<String, String>> seenHeaders,
                                                      AtomicReference<String> seenUri) throws Exception {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            try (Socket socket = server.accept()) {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                seenUri.set(in.readLine().split(" ")[1]);
                Map<String, String> headers = new LinkedHashMap<>();
                for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                    int colon = line.indexOf(':');
                    headers.put(line.substring(0, colon).toLowerCase(java.util.Locale.ROOT), line.substring(colon + 1).trim());
                }
                seenHeaders.set(headers);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: "
                        + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(bytes);
                out.flush();
            } catch (Exception ignored) {
                // server closed by the test, or the client hung up
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        PionexFuturesProperties props = new PionexFuturesProperties();
        props.setBaseUrl("http://127.0.0.1:" + server.getLocalPort());
        props.setApiKey("the-api-key");
        props.setApiSecret("the-api-secret");
        return new PionexFuturesHttpService(props);
    }

    @Test
    void getBookTickerSendsNoCredentialsToThePublicEndpoint() throws Exception {
        var headers = new AtomicReference<Map<String, String>>();
        var uri = new AtomicReference<String>();
        PionexFuturesHttpService service = serviceAnswering(200,
                "{\"result\":true,\"data\":{\"tickers\":[{\"symbol\":\"BTC_USDT\",\"bidPrice\":\"78080.00\"}]}}",
                headers, uri);

        Map<String, Object> resp = service.getBookTicker("BTC_USDT");

        assertThat(resp).containsEntry("result", true);
        assertThat(uri.get()).isEqualTo("/api/v1/market/bookTickers?symbol=BTC_USDT");
        assertThat(headers.get().containsKey("pionex-key")).isFalse();
        assertThat(headers.get().containsKey("pionex-signature")).isFalse();
    }

    @Test
    void signedCallsStillCarryTheKeyAndSignature() throws Exception {
        var headers = new AtomicReference<Map<String, String>>();
        PionexFuturesHttpService service = serviceAnswering(200,
                "{\"result\":true,\"data\":{\"balances\":[]}}", headers, new AtomicReference<>());

        service.getBalances();

        assertThat(headers.get().get("pionex-key")).isEqualTo("the-api-key");
        assertThat(headers.get().get("pionex-signature")).isNotBlank();
    }

    @Test
    void getBookTickerReturnsAFalseResultAsAMapInsteadOfThrowing() throws Exception {
        // MARKET_INVALID_SYMBOL arrives as HTTP 200 + result:false; the caller must be able to tell
        // "no such pair" apart from a failed call, so there is deliberately no requireOk here.
        PionexFuturesHttpService service = serviceAnswering(200,
                "{\"result\":false,\"code\":\"MARKET_INVALID_SYMBOL\",\"message\":\"symbol error\"}",
                new AtomicReference<>(), new AtomicReference<>());

        Map<String, Object> resp = service.getBookTicker("NOSUCHCOIN_USDT");

        assertThat(resp).containsEntry("result", false).containsEntry("code", "MARKET_INVALID_SYMBOL");
    }

    @Test
    void getBookTickerWrapsAnUnreadableBodyInTheSameExceptionShapeAsSignedCalls() throws Exception {
        PionexFuturesHttpService service = serviceAnswering(200, "<html>proxy error page</html>",
                new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> service.getBookTicker("BTC_USDT"))
                .isInstanceOf(ExchangeException.class);
    }

    @Test
    void getBookTickerThrowsOnAServerError() throws Exception {
        PionexFuturesHttpService service = serviceAnswering(500, "{\"message\":\"boom\"}",
                new AtomicReference<>(), new AtomicReference<>());

        assertThatThrownBy(() -> service.getBookTicker("BTC_USDT"))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void trueResultPassesThrough() {
        Map<String, Object> root = Map.of("result", true, "data", Map.of("balances", "ok"));

        assertThat(PionexFuturesHttpService.requireOk(root)).isSameAs(root);
    }

    @Test
    void falseResultThrowsWithCodeAndMessage() {
        Map<String, Object> root = Map.of("result", false, "code", "TRADE_PARAMETER_ERROR",
                "message", "empty `orderId`");

        assertThatThrownBy(() -> PionexFuturesHttpService.requireOk(root))
                .isInstanceOf(ExchangeException.class)
                .hasMessageContaining("empty `orderId`");
    }

    @Test
    void missingResultFieldThrowsRatherThanBeingTreatedAsSuccess() {
        // PR review fix: the class javadoc says `result` is present on every real response, so its
        // absence isn't "ok by default" — it's an envelope this client doesn't recognise (proxy
        // page, HTML error, schema change), which must never look like a successful call.
        Map<String, Object> malformed = Map.of("data", "whatever");

        assertThatThrownBy(() -> PionexFuturesHttpService.requireOk(malformed))
                .isInstanceOf(ExchangeException.class);
    }
}
