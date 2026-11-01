package com.sonar.trading.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Symbols excluded from the universe before the LLM ever sees them. */
@Entity
@Table(name = "symbol_blacklist", schema = "trading")
public class SymbolBlacklistEntity {

    @Id
    @Column(length = 20)
    private String symbol;

    @Column(length = 300)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    protected SymbolBlacklistEntity() {}

    public SymbolBlacklistEntity(String symbol, String reason) {
        this.symbol = symbol;
        this.reason = reason;
        this.createdAt = System.currentTimeMillis();
    }

    public String getSymbol() { return symbol; }
    public String getReason() { return reason; }
    public long getCreatedAt() { return createdAt; }
}
