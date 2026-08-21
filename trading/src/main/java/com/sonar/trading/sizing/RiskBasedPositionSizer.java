package com.sonar.trading.sizing;

import com.sonar.trading.risk.AccountRiskProfile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Risk-based sizing (4.2) — the strict order matters, do not reshuffle:
 * <pre>
 * riskUsd  = equity × riskPctPerTrade
 * qty      = riskUsd / |entry − sl|
 * notional = qty × entry
 * if notional > maxPositionNotional:  qty = maxPositionNotional / entry
 * if notional > equity × maxLeverage: qty = equity × maxLeverage / entry
 * qty = QuantityNormalizer.clamp(qty)
 * actualRisk = qty × |entry − sl|
 * if actualRisk > riskUsd × 1.05: REJECT — minNotional pushed the risk past the limit
 * </pre>
 */
@Component
public class RiskBasedPositionSizer {

    private static final BigDecimal REJECT_TOLERANCE = new BigDecimal("1.05");
    private static final int SCALE = 10;

    private final QuantityNormalizer normalizer;

    public RiskBasedPositionSizer(QuantityNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public SizingResult size(AccountRiskProfile profile, String venue, String symbol,
                             BigDecimal equity, BigDecimal entry, BigDecimal sl) {
        if (equity == null || equity.signum() <= 0) {
            return new SizingResult.Rejected("эквити недоступно или неположительно");
        }
        BigDecimal slDistance = entry.subtract(sl).abs();
        if (slDistance.signum() <= 0) {
            return new SizingResult.Rejected("SL совпадает с ценой входа");
        }

        BigDecimal riskUsd = equity.multiply(profile.riskPctPerTrade());
        BigDecimal qty = riskUsd.divide(slDistance, SCALE, RoundingMode.DOWN);

        BigDecimal notional = qty.multiply(entry);
        if (notional.compareTo(profile.maxPositionNotional()) > 0) {
            qty = profile.maxPositionNotional().divide(entry, SCALE, RoundingMode.DOWN);
        }

        BigDecimal maxLeverageNotional = equity.multiply(BigDecimal.valueOf(profile.maxLeverage()));
        notional = qty.multiply(entry);
        if (notional.compareTo(maxLeverageNotional) > 0) {
            qty = maxLeverageNotional.divide(entry, SCALE, RoundingMode.DOWN);
        }

        BigDecimal normalizedQty;
        try {
            normalizedQty = normalizer.normalize(venue, symbol, qty, entry);
        } catch (IllegalArgumentException e) {
            return new SizingResult.Rejected("не прошёл биржевые минималки: " + e.getMessage());
        }

        BigDecimal actualRisk = normalizedQty.multiply(slDistance);
        if (actualRisk.compareTo(riskUsd.multiply(REJECT_TOLERANCE)) > 0) {
            return new SizingResult.Rejected(
                    "minNotional вытолкнул риск за лимит: план $" + riskUsd.setScale(2, RoundingMode.HALF_UP)
                            + ", факт $" + actualRisk.setScale(2, RoundingMode.HALF_UP));
        }

        return new SizingResult.Approved(normalizedQty, normalizedQty.multiply(entry), actualRisk);
    }
}
