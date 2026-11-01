package com.sonar.trading.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MsgTest {

    /** The same cases run against the bot (strategy/src/i18n) and the admin panel (admin/i18n.js). */
    @Test
    void rendersTheSharedCases() throws Exception {
        try (InputStream in = MsgTest.class.getResourceAsStream("/i18n/render-cases.json")) {
            assertThat(in).isNotNull();
            for (JsonNode c : new ObjectMapper().readTree(in)) {
                Map<String, String> params = new LinkedHashMap<>();
                c.get("params").fields().forEachRemaining(e -> params.put(e.getKey(), e.getValue().asText()));
                assertThat(Msg.render(c.get("template").asText(), params))
                        .as(c.get("name").asText())
                        .isEqualTo(c.get("expected").asText());
            }
        }
    }

    @Test
    void ofTreatsNullValueAsAbsentAndKeepsTheRest() {
        Msg msg = Msg.of(MessageCode.POSITION_CLOSED, "symbol", "BTCUSDT", "pnl", null, "hours", 1, "minutes", 5);
        assertThat(msg.params()).containsOnlyKeys("symbol", "hours", "minutes");
        assertThat(msg.text()).isEqualTo("BTCUSDT closed, held 1h5m");
    }

    @Test
    void ofRejectsAnOddNumberOfArguments() {
        assertThatThrownBy(() -> Msg.of(MessageCode.POSITION_ADOPTED, "side"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
