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
     * Причина auto-pause — определяет, как пауза снимается (ADR-0018).
     *
     * <p>{@link #DAILY_LOSS} привязан к торговым суткам: лимит дневного убытка обнуляется на смене
     * UTC-даты вместе с {@code daily_pnl}, поэтому пауза снимается автоматически. Раньше она жила
     * вечно и переживала свою причину — платформа молча не торговала, пока кто-нибудь не заметит.
     *
     * <p>{@link #MAX_DRAWDOWN} считается от пика equity и ко дню не привязан: смена даты его не
     * лечит. Такая пауза снимается только руками, после разбора.
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
