package com.sonar.trading.risk;

import java.math.BigDecimal;

public record RiskDecision(
        Outcome outcome,
        String reason,
        BigDecimal sizeMultiplier,
        PauseCause pauseCause
) {
    public enum Outcome {
        APPROVE,
        REJECT,
        REDUCE_SIZE,
        CLOSE_ONLY,
        PAUSE_TRADING
    }

    /**
     * Auto-pause cause — determines how the pause is lifted (ADR-0018).
     *
     * <p>{@link #DAILY_LOSS} is tied to the trading day: the daily loss limit resets on the UTC date
     * change together with {@code daily_pnl}, so the pause is lifted automatically. It used to live
     * forever and outlived its cause — the platform silently stopped trading until someone noticed.
     *
     * <p>{@link #MAX_DRAWDOWN} is measured from the equity peak and is not tied to a day: a date change does not
     * cure it. Such a pause is lifted only manually, after investigation.
     */
    public enum PauseCause {
        DAILY_LOSS,
        MAX_DRAWDOWN
    }

    public static RiskDecision approve() {
        return new RiskDecision(Outcome.APPROVE, null, null, null);
    }

    public static RiskDecision reject(String reason) {
        return new RiskDecision(Outcome.REJECT, reason, null, null);
    }

    public static RiskDecision reduceSize(BigDecimal multiplier, String reason) {
        return new RiskDecision(Outcome.REDUCE_SIZE, reason, multiplier, null);
    }

    public static RiskDecision closeOnly(String reason) {
        return new RiskDecision(Outcome.CLOSE_ONLY, reason, null, null);
    }

    public static RiskDecision pauseTrading(PauseCause cause, String reason) {
        return new RiskDecision(Outcome.PAUSE_TRADING, reason, null, cause);
    }

    public boolean isBlocking() {
        return outcome == Outcome.REJECT || outcome == Outcome.PAUSE_TRADING;
    }
}
