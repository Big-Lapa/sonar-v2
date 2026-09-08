package com.sonar.trading.exchange.pionex.http;

import com.sonar.trading.exchange.exception.ExchangeException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code requireOk} is package-private specifically so it can be unit-tested without HTTP — same
 * rationale as {@code BybitFuturesHttpService.requireOk}.
 */
class PionexFuturesHttpServiceTest {

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
