package com.sonar.trading.execution.gateway;

import com.sonar.trading.exchange.exception.ExchangeException;

/**
 * Decides whether a failure is transient and therefore safe to retry (T16, ADR-0007).
 *
 * <p>Transient = network/timeout ({@code IO_ERROR}, {@code BINGX_HTTP_ERROR}), HTTP 5xx, or 429
 * (rate limit). The same {@code clientOrderId} is reused on retry so BingX deduplicates — a retry
 * never creates a second order.
 *
 * <p>Everything else (business 4xx, parse errors, non-{@link ExchangeException} faults) is treated
 * as permanent: retrying would not help and could mask a real rejection.
 *
 * <p>{@code IO_ERROR_AMBIGUOUS} (read timeout on a non-idempotent call — the exchange may have
 * accepted the order) is deliberately NOT transient: BingX does not document clientOrderId dedup,
 * so a blind retry could double the position. Resolution is left to PendingSubmitRecovery /
 * reconciliation, which query the actual order state.
 */
public final class TransientErrorClassifier {

    private TransientErrorClassifier() {}

    public static boolean isTransient(Throwable t) {
        if (!(t instanceof ExchangeException ee) || ee.getError() == null) {
            return false;
        }
        String code = ee.getError().code();
        if (code == null) {
            return false;
        }
        if ("IO_ERROR".equals(code) || "BINGX_HTTP_ERROR".equals(code)
                || "BINANCE_HTTP_ERROR".equals(code) || "429".equals(code)) {
            return true;
        }
        try {
            int http = Integer.parseInt(code);
            return http >= 500 && http < 600;
        } catch (NumberFormatException notHttp) {
            return false;
        }
    }

    /**
     * Stricter gate for a non-idempotent ENTRY place. A retry reuses the same {@code clientOrderId},
     * but BingX does not document clientOrderId dedup, so retrying is only safe when nothing could
     * have been created on the exchange:
     * <ul>
     *   <li>{@code IO_ERROR} — connect-phase failure, the request was never sent;</li>
     *   <li>{@code 429} — rate-limit rejection, the order is refused before it is created.</li>
     * </ul>
     *
     * <p>Everything else that {@link #isTransient} accepts (HTTP 5xx, {@code BINGX_HTTP_ERROR},
     * {@code BINANCE_HTTP_ERROR}) means the exchange may already have received and created the
     * order — retrying could double the position. Those are routed to {@code IO_ERROR_AMBIGUOUS}
     * resolution instead (see {@code ResilientExecutionGateway#invoke}), exactly like a read timeout.
     */
    static boolean isRetryableForEntry(Throwable t) {
        if (!isTransient(t)) {
            return false;
        }
        // isTransient guarantees an ExchangeException with a non-null error code.
        String code = ((ExchangeException) t).getError().code();
        return "IO_ERROR".equals(code) || "429".equals(code);
    }
}
