package com.sonar.trading.domain.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "trading_state", schema = "trading")
public class TradingStateEntity {

    @Id
    @Column(nullable = false)
    private String key;

    @Column(nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    protected TradingStateEntity() {}

    public TradingStateEntity(String key, String value) {
        this.key = key;
        this.value = value;
        this.updatedAt = System.currentTimeMillis();
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public long getUpdatedAt() { return updatedAt; }

    public void setValue(String value) {
        this.value = value;
        this.updatedAt = System.currentTimeMillis();
    }
}
