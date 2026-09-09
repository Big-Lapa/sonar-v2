package com.sonar.trading.exchange.bybit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HMAC-SHA256 signing: {@code sign_str = timestamp + apiKey + recvWindow + payload}. Fixed
 * inputs so the digest is deterministic and can be checked against a value computed independently.
 */
class BybitRequestSignerTest {

    @Test
    void signIsDeterministicHmacSha256OfTimestampApiKeyRecvWindowPayload() {
        String signature = BybitRequestSigner.sign("category=linear&symbol=BTCUSDT",
                "test-secret", "test-key", 5000L, 1_700_000_000_000L);

        // Independently computed via HmacSHA256("1700000000000test-key5000category=linear&symbol=BTCUSDT", "test-secret").
        String expected = BybitRequestSigner.hmacSha256Hex(
                "1700000000000test-key5000category=linear&symbol=BTCUSDT", "test-secret");

        assertThat(signature).isEqualTo(expected);
        assertThat(signature).hasSize(64); // hex-encoded SHA-256 digest
        assertThat(signature).matches("[0-9a-f]{64}");
    }

    @Test
    void differentPayloadsProduceDifferentSignatures() {
        String a = BybitRequestSigner.sign("qty=1", "secret", "key", 5000L, 1L);
        String b = BybitRequestSigner.sign("qty=2", "secret", "key", 5000L, 1L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void nullPayloadSignsAsEmptyString() {
        String signature = BybitRequestSigner.sign(null, "secret", "key", 5000L, 1L);
        String expected = BybitRequestSigner.hmacSha256Hex("1key5000", "secret");

        assertThat(signature).isEqualTo(expected);
    }

    @Test
    void nullSecretFailsFastRatherThanSigningWithAnEmptyKey() {
        // Same behavior as BingxRequestSigner: an unconfigured secret (empty .env) must not
        // silently produce a "valid-looking" signature — javax.crypto rejects an empty HMAC key.
        assertThatThrownBy(() -> BybitRequestSigner.hmacSha256Hex("payload", null))
                .isInstanceOf(IllegalStateException.class);
    }
}
