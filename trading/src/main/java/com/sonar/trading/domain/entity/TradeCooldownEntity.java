package com.sonar.trading.domain.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "trade_cooldowns", schema = "trading")
public class TradeCooldownEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(nullable = false)
    private String symbol;

    @Column(name = "last_loss_at", nullable = false)
    private long lastLossAt;

    protected TradeCooldownEntity() {}

    public TradeCooldownEntity(String accountId, String symbol, long lastLossAt) {
        this.accountId = accountId;
        this.symbol = symbol;
        this.lastLossAt = lastLossAt;
    }

    public Long getId() { return id; }
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public long getLastLossAt() { return lastLossAt; }
    public void setLastLossAt(long lastLossAt) { this.lastLossAt = lastLossAt; }
}
