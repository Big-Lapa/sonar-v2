package com.sonar.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * v2's execution surface is far smaller than v1's (no maker-close, no per-strategy TP/SL
 * percentages, no global exchange mode) — TP/SL are always absolute prices from the proposal's
 * levels (4.6). The only thing the execution adapters still need from config is which price
 * source each venue's conditional TP/SL orders trigger against.
 */
@Component
@ConfigurationProperties(prefix = "trading")
public class TradingProperties {

    private final TpSlProps tpSl = new TpSlProps();
    private final BybitProps bybit = new BybitProps();

    public TpSlProps getTpSl() { return tpSl; }
    public BybitProps getBybit() { return bybit; }

    public static class TpSlProps {
        /** BingX working type for conditional TP/SL orders: MARK_PRICE or CONTRACT_PRICE. */
        private String workingType = "MARK_PRICE";

        public String getWorkingType() { return workingType; }
        public void setWorkingType(String workingType) { this.workingType = workingType; }
    }

    public static class BybitProps {
        /** Bybit trigger price source for conditional TP/SL orders: LastPrice, MarkPrice or IndexPrice. */
        private String triggerBy = "MarkPrice";

        public String getTriggerBy() { return triggerBy; }
        public void setTriggerBy(String triggerBy) { this.triggerBy = triggerBy; }
    }
}
