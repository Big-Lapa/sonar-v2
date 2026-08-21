package com.sonar.trading.sizing;

import com.sonar.trading.risk.AccountRiskProfile;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** 4.2 sizing formula: strict order of the three clamps, and the minNotional-past-limit reject. */
class RiskBasedPositionSizerTest {

    private static AccountRiskProfile profile(String riskPct, String maxNotional, int maxLeverage) {
        return new AccountRiskProfile("acct-1", new BigDecimal(riskPct), new BigDecimal("0.02"),
                new BigDecimal(maxNotional), new BigDecimal("0.03"), new BigDecimal("0.15"),
                5, maxLeverage, true);
    }

    @Test
    void approvesWhenWithinAllLimits() {
        QuantityNormalizer normalizer = Mockito.mock(QuantityNormalizer.class);
        when(normalizer.normalize(anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2)); // pass qty through unchanged
        RiskBasedPositionSizer sizer = new RiskBasedPositionSizer(normalizer);

        // equity=10000, riskPct=0.5% -> riskUsd=50; entry=100, sl=95 -> slDist=5 -> qty=10
        SizingResult result = sizer.size(profile("0.005", "2000", 5), "BINANCE", "SOLUSDT",
                new BigDecimal("10000"), new BigDecimal("100"), new BigDecimal("95"));

        assertThat(result).isInstanceOf(SizingResult.Approved.class);
        SizingResult.Approved approved = (SizingResult.Approved) result;
        assertThat(approved.qty()).isEqualByComparingTo("10");
        assertThat(approved.actualRisk()).isEqualByComparingTo("50");
    }

    @Test
    void maxPositionNotionalCapsQtyBeforeLeverageCheck() {
        QuantityNormalizer normalizer = Mockito.mock(QuantityNormalizer.class);
        when(normalizer.normalize(anyString(), anyString(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        RiskBasedPositionSizer sizer = new RiskBasedPositionSizer(normalizer);

        // riskUsd = 1,000,000 * 0.5 = 500000; slDist=1 -> raw qty 500000 -> notional 50,000,000
        // way past maxPositionNotional=2000 -> qty clamped to 2000/100 = 20.
        SizingResult result = sizer.size(profile("0.5", "2000", 5), "BINANCE", "SOLUSDT",
                new BigDecimal("1000000"), new BigDecimal("100"), new BigDecimal("99"));

        assertThat(result).isInstanceOf(SizingResult.Approved.class);
        assertThat(((SizingResult.Approved) result).qty()).isEqualByComparingTo("20");
    }

    /** 2026-08-21: exchange minimum pushing actual risk above plan is now ACCEPTED, not rejected —
     *  as long as the resulting notional still fits the account's own caps (see the next test for
     *  the case where it doesn't). */
    @Test
    void acceptsExchangeMinimumEvenWhenActualRiskExceedsPlan() {
        QuantityNormalizer normalizer = Mockito.mock(QuantityNormalizer.class);
        // Exchange minQty forces a larger qty than the risk formula asked for, but still cheap
        // enough in notional terms to fit maxPositionNotional=2000 (qty=10 * entry=100 = 1000).
        when(normalizer.normalize(anyString(), anyString(), any(), any()))
                .thenReturn(new BigDecimal("10"));
        RiskBasedPositionSizer sizer = new RiskBasedPositionSizer(normalizer);

        // riskUsd = 10000*0.005 = 50; slDist=5 -> formula wants qty=1, exchange forces qty=10
        // -> actualRisk = 10*5 = 50, exactly at plan here, but the point is: no ceiling check.
        SizingResult result = sizer.size(profile("0.005", "2000", 5), "BINANCE", "SOLUSDT",
                new BigDecimal("10000"), new BigDecimal("100"), new BigDecimal("95"));

        assertThat(result).isInstanceOf(SizingResult.Approved.class);
        assertThat(((SizingResult.Approved) result).qty()).isEqualByComparingTo("10");
    }

    @Test
    void rejectsWhenExchangeMinimumExceedsAccountCaps() {
        QuantityNormalizer normalizer = Mockito.mock(QuantityNormalizer.class);
        // Exchange forces qty=1000 -> notional 100,000, way past maxPositionNotional=2000 and
        // past equity*maxLeverage too (10000*5=50000) — genuinely untradeable within account limits.
        when(normalizer.normalize(anyString(), anyString(), any(), any()))
                .thenReturn(new BigDecimal("1000"));
        RiskBasedPositionSizer sizer = new RiskBasedPositionSizer(normalizer);

        SizingResult result = sizer.size(profile("0.005", "2000", 5), "BINANCE", "SOLUSDT",
                new BigDecimal("10000"), new BigDecimal("100"), new BigDecimal("95"));

        assertThat(result).isInstanceOf(SizingResult.Rejected.class);
        assertThat(((SizingResult.Rejected) result).reason()).contains("превышает лимит аккаунта");
    }

    @Test
    void rejectsWhenNormalizerCannotClampAtAll() {
        QuantityNormalizer normalizer = Mockito.mock(QuantityNormalizer.class);
        when(normalizer.normalize(anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalArgumentException("qty 0.0000001 < minQty 0.001 for SOLUSDT"));
        RiskBasedPositionSizer sizer = new RiskBasedPositionSizer(normalizer);

        SizingResult result = sizer.size(profile("0.005", "2000", 5), "BINANCE", "SOLUSDT",
                new BigDecimal("10000"), new BigDecimal("100"), new BigDecimal("95"));

        assertThat(result).isInstanceOf(SizingResult.Rejected.class);
        assertThat(((SizingResult.Rejected) result).reason()).contains("минималки");
    }

    @Test
    void rejectsWhenEntryEqualsStopLoss() {
        QuantityNormalizer normalizer = Mockito.mock(QuantityNormalizer.class);
        RiskBasedPositionSizer sizer = new RiskBasedPositionSizer(normalizer);

        SizingResult result = sizer.size(profile("0.005", "2000", 5), "BINANCE", "SOLUSDT",
                new BigDecimal("10000"), new BigDecimal("100"), new BigDecimal("100"));

        assertThat(result).isInstanceOf(SizingResult.Rejected.class);
        Mockito.verifyNoInteractions(normalizer);
    }
}
