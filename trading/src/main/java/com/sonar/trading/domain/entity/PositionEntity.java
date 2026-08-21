package com.sonar.trading.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "positions", schema = "trading")
public class PositionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Optimistic lock (gap-review #1). Two writers share this row: the signal pipeline's Tx2
     * (entity loaded BEFORE the exchange HTTP call, merged after — seconds-wide window) and the
     * reconciler. Without the version a reconciler overwrite in that window was silently lost.
     */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "position_id", nullable = false, unique = true)
    private String positionId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(nullable = false)
    private String exchange;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side;

    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal quantity;

    @Column(name = "entry_price", precision = 30, scale = 10)
    private BigDecimal entryPrice;

    @Column(name = "mark_price", precision = 30, scale = 10)
    private BigDecimal markPrice;

    @Column(name = "unrealized_pnl", precision = 30, scale = 10)
    private BigDecimal unrealizedPnl;

    @Column(name = "liquidation_price", precision = 30, scale = 10)
    private BigDecimal liquidationPrice;

    @Column(name = "realized_pnl", nullable = false, precision = 30, scale = 10)
    private BigDecimal realizedPnl;

    private Integer leverage;

    @Column(nullable = false)
    private String status;

    @Column(name = "opened_at")
    private Long openedAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    /** Structural SL/TP levels and the planned risk fixed at open (4.1/4.2) — decimal strings. */
    @Column(name = "sl_price", length = 30)
    private String slPrice;

    @Column(name = "tp_price", length = 30)
    private String tpPrice;

    @Column(name = "planned_risk_amount", length = 30)
    private String plannedRiskAmount;

    protected PositionEntity() {}

    public static PositionEntity newFlat(String accountId, String exchange, String symbol) {
        PositionEntity p = new PositionEntity();
        p.positionId = accountId + ":" + symbol;
        p.accountId = accountId;
        p.exchange = exchange;
        p.symbol = symbol;
        p.side = "FLAT";
        p.quantity = BigDecimal.ZERO;
        p.realizedPnl = BigDecimal.ZERO;
        p.status = "CLOSED";
        p.updatedAt = System.currentTimeMillis();
        return p;
    }

    public Long getId() { return id; }
    public long getVersion() { return version; }
    public String getPositionId() { return positionId; }
    public String getAccountId() { return accountId; }
    public String getExchange() { return exchange; }
    public String getSymbol() { return symbol; }
    public String getSide() { return side; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public BigDecimal getMarkPrice() { return markPrice; }
    public BigDecimal getUnrealizedPnl() { return unrealizedPnl; }
    public BigDecimal getLiquidationPrice() { return liquidationPrice; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public Integer getLeverage() { return leverage; }
    public String getStatus() { return status; }
    public Long getOpenedAt() { return openedAt; }
    public long getUpdatedAt() { return updatedAt; }

    public void setSide(String side) { this.side = side; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public void setMarkPrice(BigDecimal markPrice) { this.markPrice = markPrice; }
    public void setUnrealizedPnl(BigDecimal unrealizedPnl) { this.unrealizedPnl = unrealizedPnl; }
    public void setLiquidationPrice(BigDecimal liquidationPrice) { this.liquidationPrice = liquidationPrice; }
    public void setLeverage(Integer leverage) { this.leverage = leverage; }
    public void setRealizedPnl(BigDecimal realizedPnl) { this.realizedPnl = realizedPnl; }
    public void setStatus(String status) { this.status = status; }
    public void setOpenedAt(Long openedAt) { this.openedAt = openedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public String getSlPrice() { return slPrice; }
    public void setSlPrice(String slPrice) { this.slPrice = slPrice; }
    public String getTpPrice() { return tpPrice; }
    public void setTpPrice(String tpPrice) { this.tpPrice = tpPrice; }
    public String getPlannedRiskAmount() { return plannedRiskAmount; }
    public void setPlannedRiskAmount(String plannedRiskAmount) { this.plannedRiskAmount = plannedRiskAmount; }
}
