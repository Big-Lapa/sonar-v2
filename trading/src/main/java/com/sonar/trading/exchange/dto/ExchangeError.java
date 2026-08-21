package com.sonar.trading.exchange.dto;

public record ExchangeError(String code, String message) {

    /**
     * True when the venue reports that the queried order does not exist:
     * BingX Swap V2 code 80016, Binance futures code -2013, or a "not exist(s)" message.
     * Callers use this to resolve a stuck command/order as terminal instead of
     * retrying the query forever.
     */
    public boolean isOrderNotFound() {
        if ("80016".equals(code) || "-2013".equals(code)) {
            return true;
        }
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("order not exist") || m.contains("order does not exist");
    }
}
