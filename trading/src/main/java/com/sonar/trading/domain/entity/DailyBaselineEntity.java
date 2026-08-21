package com.sonar.trading.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Daily accounting baseline (4.2). MUST live in DB — a restart mid-day must not reset the
 * daily-loss limit or forget the running peak equity. One row per (account, UTC trading day).
 */
@Entity
@Table(name = "daily_baseline", schema = "trading")
public class DailyBaselineEntity {

    @EmbeddedId
    private Key id;

    @Column(name = "equity_at_open", nullable = false, length = 30)
    private String equityAtOpen;

    @Column(name = "realized_pnl", nullable = false, length = 30)
    private String realizedPnl = "0";

    @Column(name = "peak_equity", nullable = false, length = 30)
    private String peakEquity;

    protected DailyBaselineEntity() {}

    public static DailyBaselineEntity open(String accountId, LocalDate tradingDay,
                                            String equityAtOpen, String peakEquity) {
        DailyBaselineEntity e = new DailyBaselineEntity();
        e.id = new Key(accountId, tradingDay);
        e.equityAtOpen = equityAtOpen;
        e.peakEquity = peakEquity;
        e.realizedPnl = "0";
        return e;
    }

    public Key getId() { return id; }
    public String getAccountId() { return id.accountId; }
    public LocalDate getTradingDay() { return id.tradingDay; }
    public String getEquityAtOpen() { return equityAtOpen; }
    public String getRealizedPnl() { return realizedPnl; }
    public void setRealizedPnl(String v) { this.realizedPnl = v; }
    public String getPeakEquity() { return peakEquity; }
    public void setPeakEquity(String v) { this.peakEquity = v; }

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "account_id", length = 100)
        private String accountId;
        @Column(name = "trading_day")
        private LocalDate tradingDay;

        protected Key() {}
        public Key(String accountId, LocalDate tradingDay) {
            this.accountId = accountId;
            this.tradingDay = tradingDay;
        }

        public String getAccountId() { return accountId; }
        public LocalDate getTradingDay() { return tradingDay; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(accountId, key.accountId) && Objects.equals(tradingDay, key.tradingDay);
        }

        @Override
        public int hashCode() { return Objects.hash(accountId, tradingDay); }
    }
}
