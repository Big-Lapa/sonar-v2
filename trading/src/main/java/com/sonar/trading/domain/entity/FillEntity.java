package com.sonar.trading.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * One execution fill (T17/FU-3). Written when reconciliation confirms an order reached a filled
 * state on the exchange. Append-only audit row; never overwritten.
 */
@Entity
@Table(name = "fills", schema = "trading")
public class FillEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal quantity;

    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal price;

    @Column(precision = 30, scale = 10)
    private BigDecimal fee;

    @Column(name = "filled_at", nullable = false)
    private long filledAt;

    protected FillEntity() {}

    public FillEntity(Long orderId, String accountId, String symbol, BigDecimal quantity, BigDecimal price,
                      BigDecimal fee, long filledAt) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
        this.fee = fee;
        this.filledAt = filledAt;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getFee() { return fee; }
    public long getFilledAt() { return filledAt; }
}
