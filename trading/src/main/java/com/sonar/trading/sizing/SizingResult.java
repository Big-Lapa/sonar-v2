package com.sonar.trading.sizing;

import com.sonar.trading.i18n.Msg;

import java.math.BigDecimal;
import java.util.Map;

/** Outcome of {@link RiskBasedPositionSizer#size} for one account leg. */
public sealed interface SizingResult {

    record Approved(BigDecimal qty, BigDecimal notional, BigDecimal actualRisk) implements SizingResult {}

    /** {@code code}/{@code params} let the admin panel and the Telegram bot translate the rejection;
     *  {@code reason} is the English fallback. */
    record Rejected(String code, Map<String, String> params, String reason) implements SizingResult {}

    static Rejected rejected(Msg msg) {
        return new Rejected(msg.code().name(), msg.params(), msg.text());
    }
}
