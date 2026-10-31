package com.sonar.trading.risk;

public interface RiskEngine {
    RiskDecision evaluate(RiskContext context);

    /** Arm auto-pause. The cause determines whether the pause clears itself on the trading-day rollover. */
    void activateAutoPause(String accountId, RiskDecision.PauseCause cause, String reason);

    void clearAutoPause(String accountId);
    boolean isAutoPauseActive(String accountId);

    /** Cause of the active pause, if known (for the ops API and logs). */
    RiskDecision.PauseCause autoPauseCause(String accountId);
}
