package com.sonar.trading.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * Tuning for the reconciliation worker (T17). Bound from {@code trading.reconciliation.*}.
 * Only consumed on the LIVE path.
 */
@ConfigurationProperties(prefix = "trading.reconciliation")
public class ReconciliationProperties {

    /** Period between periodic sync cycles while at least one position is open, in ms. */
    private long intervalMs = 5_000;

    /**
     * Period between periodic sync cycles while NO position is open, in ms. Идея адаптивной
     * каденции: выход из позиции — только биржевыми SL/TP, т.е. биржа закрывает позицию БЕЗ
     * нашего ордера, и узнаём мы об этом лишь из recon. Пока позиция висит, задержка цикла =
     * задержка realized PnL, события CLOSED и освобождения символа под следующий вход, поэтому
     * там держим {@link #intervalMs}. Во флэте узнавать не о чем — частый опрос биржи там чистый
     * трафик. Реализовано пропуском тиков планировщика, а не сменой расписания.
     */
    private long idleIntervalMs = 60_000;

    /** Account asset whose balance/equity drives PnL and balance.updated (BingX VST settles USDT). */
    private String equityAsset = "USDT";

    /** Absolute quantity difference below which local vs exchange position size is considered equal. */
    private BigDecimal qtyEpsilon = new BigDecimal("0.0000001");

    /** Absolute price difference below which local vs exchange avg/entry price is considered equal. */
    private BigDecimal priceEpsilon = new BigDecimal("0.00000001");

    /**
     * Minimum age of a position row before an in-sync mark-to-market rewrites its runtime fields.
     * Recon runs every {@link #intervalMs}; without this, every open position writes a row and a
     * {@code trading.state} event on every cycle. 0 disables the throttle (refresh every cycle).
     */
    private long runtimeRefreshMs = 15_000;

    public long getIntervalMs() { return intervalMs; }
    public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
    public long getIdleIntervalMs() { return idleIntervalMs; }
    public void setIdleIntervalMs(long idleIntervalMs) { this.idleIntervalMs = idleIntervalMs; }
    public long getRuntimeRefreshMs() { return runtimeRefreshMs; }
    public void setRuntimeRefreshMs(long runtimeRefreshMs) { this.runtimeRefreshMs = runtimeRefreshMs; }
    public String getEquityAsset() { return equityAsset; }
    public void setEquityAsset(String equityAsset) { this.equityAsset = equityAsset; }
    public BigDecimal getQtyEpsilon() { return qtyEpsilon; }
    public void setQtyEpsilon(BigDecimal qtyEpsilon) { this.qtyEpsilon = qtyEpsilon; }
    public BigDecimal getPriceEpsilon() { return priceEpsilon; }
    public void setPriceEpsilon(BigDecimal priceEpsilon) { this.priceEpsilon = priceEpsilon; }
}
