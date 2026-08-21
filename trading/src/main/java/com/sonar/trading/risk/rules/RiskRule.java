package com.sonar.trading.risk.rules;

import com.sonar.trading.risk.RiskContext;
import com.sonar.trading.risk.RiskDecision;

import java.util.Optional;

public interface RiskRule {
    /**
     * Evaluate the rule. Returns empty if the rule passes, or a decision if it fires.
     */
    Optional<RiskDecision> evaluate(RiskContext context);

    int priority();
}
