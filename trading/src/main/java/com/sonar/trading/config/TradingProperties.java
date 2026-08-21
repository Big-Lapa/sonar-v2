package com.sonar.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * v2's execution surface is far smaller than v1's (no maker-close, no per-strategy TP/SL
 * percentages, no global exchange mode) — TP/SL are always absolute prices from the proposal's
 * levels (4.6). The only thing {@code LiveExecutionAdapter} still needs from config is which
 * price BingX's attached TP/SL orders trigger against.
 */
@Component
@ConfigurationProperties(prefix = "trading")
public class TradingProperties {

    private final TpSlProps tpSl = new TpSlProps();

    public TpSlProps getTpSl() { return tpSl; }

    public static class TpSlProps {
        /** BingX working type for conditional TP/SL orders: MARK_PRICE or CONTRACT_PRICE. */
        private String workingType = "MARK_PRICE";

        public String getWorkingType() { return workingType; }
        public void setWorkingType(String workingType) { this.workingType = workingType; }
    }
}
