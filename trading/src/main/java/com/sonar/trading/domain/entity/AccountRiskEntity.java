package com.sonar.trading.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Per-account risk profile. No per-symbol profiles exist in v2. */
@Entity
@Table(name = "account_risk", schema = "trading")
public class AccountRiskEntity {

    @Id
    @Column(name = "account_id", length = 100)
    private String accountId;

    @Column(name = "risk_pct_per_trade", nullable = false, length = 30)
    private String riskPctPerTrade = "0.005";

    @Column(name = "max_portfolio_risk_pct", nullable = false, length = 30)
    private String maxPortfolioRiskPct = "0.02";

    @Column(name = "max_position_notional", nullable = false, length = 30)
    private String maxPositionNotional = "2000";

    @Column(name = "max_daily_realized_loss_pct", nullable = false, length = 30)
    private String maxDailyRealizedLossPct = "0.03";

    @Column(name = "max_equity_drawdown_pct", nullable = false, length = 30)
    private String maxEquityDrawdownPct = "0.15";

    @Column(name = "max_open_positions", nullable = false)
    private int maxOpenPositions = 5;

    @Column(name = "max_leverage", nullable = false)
    private int maxLeverage = 5;

    @Column(nullable = false)
    private boolean tradable = false;

    /** Gates {@code MinutePoller}'s monitoring (balance/positions/stop-verify/close-notify) —
     *  separate from {@link #tradable}, which gates only whether Sonar may itself open a
     *  position here. Default true: a freshly-registered account is watched immediately, same as
     *  every other account, even before anyone decides whether Sonar should be allowed to trade
     *  it (which stays opt-in via {@code tradable}, default false). */
    @Column(nullable = false)
    private boolean active = true;

    @Version
    @Column(nullable = false)
    private int version = 1;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    protected AccountRiskEntity() {}

    public static AccountRiskEntity defaultsFor(String accountId) {
        AccountRiskEntity e = new AccountRiskEntity();
        e.accountId = accountId;
        e.updatedAt = System.currentTimeMillis();
        return e;
    }

    public String getAccountId() { return accountId; }
    public String getRiskPctPerTrade() { return riskPctPerTrade; }
    public void setRiskPctPerTrade(String v) { this.riskPctPerTrade = v; }
    public String getMaxPortfolioRiskPct() { return maxPortfolioRiskPct; }
    public void setMaxPortfolioRiskPct(String v) { this.maxPortfolioRiskPct = v; }
    public String getMaxPositionNotional() { return maxPositionNotional; }
    public void setMaxPositionNotional(String v) { this.maxPositionNotional = v; }
    public String getMaxDailyRealizedLossPct() { return maxDailyRealizedLossPct; }
    public void setMaxDailyRealizedLossPct(String v) { this.maxDailyRealizedLossPct = v; }
    public String getMaxEquityDrawdownPct() { return maxEquityDrawdownPct; }
    public void setMaxEquityDrawdownPct(String v) { this.maxEquityDrawdownPct = v; }
    public int getMaxOpenPositions() { return maxOpenPositions; }
    public void setMaxOpenPositions(int v) { this.maxOpenPositions = v; }
    public int getMaxLeverage() { return maxLeverage; }
    public void setMaxLeverage(int v) { this.maxLeverage = v; }
    public boolean isTradable() { return tradable; }
    public void setTradable(boolean v) { this.tradable = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public int getVersion() { return version; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long v) { this.updatedAt = v; }
}
