package com.sonar.trading.sizing;

import com.sonar.trading.risk.AccountRiskProfile;
import org.springframework.stereotype.Component;

import com.sonar.trading.i18n.MessageCode;
import com.sonar.trading.i18n.Msg;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Risk-based sizing — the strict order matters, do not reshuffle:
 * <pre>
 * riskUsd  = equity × riskPctPerTrade
 * qty      = riskUsd / |entry − sl|
 * notional = qty × entry
 * if notional > maxPositionNotional:  qty = maxPositionNotional / entry
 * if notional > equity × maxLeverage: qty = equity × maxLeverage / entry
 * qty = QuantityNormalizer.clamp(qty)   // bumps UP to exchange minQty/minNotional if needed
 * actualRisk = qty × |entry − sl|
 * </pre>
 *
 * <p>Decided with the user 2026-08-21: a far SL naturally computes a small risk-based qty, and
 * when the exchange's minQty/minNotional floor is above that, we now open at the exchange
 * minimum and accept actualRisk > planned riskUsd — rather than rejecting a perfectly tradeable
 * setup just because the exchange floor doesn't fit the risk plan exactly. The ONE thing we still
 * refuse is the exchange floor pushing the position past the account's OWN caps
 * (max_position_notional / max_leverage) — that's not a rounding nuisance, it's the same
 * account-level limit the earlier notional/leverage clamps in this method exist to enforce.
 */
@Component
public class RiskBasedPositionSizer {

    private static final int SCALE = 10;

    private final QuantityNormalizer normalizer;

    public RiskBasedPositionSizer(QuantityNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    public SizingResult size(AccountRiskProfile profile, String accountId, String symbol,
                             BigDecimal equity, BigDecimal entry, BigDecimal sl) {
        if (equity == null || equity.signum() <= 0) {
            return SizingResult.rejected(Msg.of(MessageCode.EQUITY_UNAVAILABLE));
        }
        BigDecimal slDistance = entry.subtract(sl).abs();
        if (slDistance.signum() <= 0) {
            return SizingResult.rejected(Msg.of(MessageCode.SL_EQUALS_ENTRY));
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
            normalizedQty = normalizer.normalize(accountId, symbol, qty, entry);
        } catch (IllegalArgumentException e) {
            return SizingResult.rejected(Msg.of(MessageCode.BELOW_EXCHANGE_MINIMUM, "error", e.getMessage()));
        }

        BigDecimal normalizedNotional = normalizedQty.multiply(entry);
        BigDecimal notionalCap = profile.maxPositionNotional().min(maxLeverageNotional);
        if (normalizedNotional.compareTo(notionalCap) > 0) {
            return SizingResult.rejected(Msg.of(MessageCode.EXCEEDS_ACCOUNT_LIMITS, "qty", normalizedQty,
                    "notional", normalizedNotional.setScale(2, RoundingMode.HALF_UP),
                    "limit", notionalCap.setScale(2, RoundingMode.HALF_UP)));
        }

        BigDecimal actualRisk = normalizedQty.multiply(slDistance);
        return new SizingResult.Approved(normalizedQty, normalizedNotional, actualRisk);
    }

    /**
     * Manual sizing (manual multi-exchange trading panel): {@code qty = notional / entry} directly
     * — no risk% math, the operator picked the notional themselves. Still clamped against the
     * account's own {@code maxPositionNotional}/{@code maxLeverage} caps and the exchange floor,
     * same safety rails as {@link #size}, so a fat-fingered notional can't silently exceed the
     * account's configured limits. {@code actualRisk} is informational (from the given SL), not
     * something this method sized against.
     */
    public SizingResult sizeManual(AccountRiskProfile profile, String accountId, String symbol,
                                   BigDecimal equity, BigDecimal notional, BigDecimal entry, BigDecimal sl) {
        if (notional == null || notional.signum() <= 0) {
            return SizingResult.rejected(Msg.of(MessageCode.NOTIONAL_MUST_BE_POSITIVE));
        }
        BigDecimal slDistance = sl != null ? entry.subtract(sl).abs() : null;
        if (sl != null && slDistance.signum() <= 0) {
            return SizingResult.rejected(Msg.of(MessageCode.SL_EQUALS_ENTRY));
        }

        // The operator typed this notional in on purpose — unlike size()'s risk%-derived qty, a cap
        // here isn't a rounding nuisance to smooth over, it's their explicit input turning out to
        // be too big. Reject with the numbers rather than silently opening 1/5th of what they asked
        // for and letting them find out from the preview qty alone.
        if (notional.compareTo(profile.maxPositionNotional()) > 0) {
            return SizingResult.rejected(Msg.of(MessageCode.NOTIONAL_EXCEEDS_POSITION_LIMIT,
                    "notional", notional.setScale(2, RoundingMode.HALF_UP),
                    "limit", profile.maxPositionNotional().setScale(2, RoundingMode.HALF_UP)));
        }
        BigDecimal maxLeverageNotional = equity != null && equity.signum() > 0
                ? equity.multiply(BigDecimal.valueOf(profile.maxLeverage())) : null;
        if (maxLeverageNotional != null && notional.compareTo(maxLeverageNotional) > 0) {
            return SizingResult.rejected(Msg.of(MessageCode.NOTIONAL_EXCEEDS_LEVERAGE_LIMIT,
                    "notional", notional.setScale(2, RoundingMode.HALF_UP),
                    "limit", maxLeverageNotional.setScale(2, RoundingMode.HALF_UP)));
        }

        BigDecimal qty = notional.divide(entry, SCALE, RoundingMode.DOWN);

        BigDecimal normalizedQty;
        try {
            normalizedQty = normalizer.normalize(accountId, symbol, qty, entry);
        } catch (IllegalArgumentException e) {
            return SizingResult.rejected(Msg.of(MessageCode.BELOW_EXCHANGE_MINIMUM, "error", e.getMessage()));
        }

        BigDecimal normalizedNotional = normalizedQty.multiply(entry);
        BigDecimal notionalCap = maxLeverageNotional != null
                ? profile.maxPositionNotional().min(maxLeverageNotional) : profile.maxPositionNotional();
        if (normalizedNotional.compareTo(notionalCap) > 0) {
            return SizingResult.rejected(Msg.of(MessageCode.EXCEEDS_ACCOUNT_LIMITS, "qty", normalizedQty,
                    "notional", normalizedNotional.setScale(2, RoundingMode.HALF_UP),
                    "limit", notionalCap.setScale(2, RoundingMode.HALF_UP)));
        }

        BigDecimal actualRisk = slDistance != null ? normalizedQty.multiply(slDistance) : null;
        return new SizingResult.Approved(normalizedQty, normalizedNotional, actualRisk);
    }
}
