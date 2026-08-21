package com.sonar.trading.sizing;

import java.math.BigDecimal;

/** Outcome of {@link RiskBasedPositionSizer#size} for one account leg (4.2). */
public sealed interface SizingResult {

    record Approved(BigDecimal qty, BigDecimal notional, BigDecimal actualRisk) implements SizingResult {}

    /** Reason is operator-facing Russian text — shown verbatim in the Telegram/admin report (4.2). */
    record Rejected(String reason) implements SizingResult {}
}
