package com.sonar.trading.risk;

public interface RiskEngine {
    RiskDecision evaluate(RiskContext context);

    /** Взвести auto-pause. Причина определяет, снимется ли пауза сама на смене торговых суток. */
    void activateAutoPause(String accountId, RiskDecision.PauseCause cause, String reason);

    void clearAutoPause(String accountId);
    boolean isAutoPauseActive(String accountId);

    /** Причина активной паузы, если известна (для ops-API и логов). */
    RiskDecision.PauseCause autoPauseCause(String accountId);
}
