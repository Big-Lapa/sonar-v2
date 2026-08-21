package com.sonar.trading.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "order_commands", schema = "trading")
public class OrderCommandEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "command_id", nullable = false, unique = true)
    private String commandId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "proposal_id", nullable = false)
    private String proposalId;

    @Column(name = "client_order_id", nullable = false, unique = true)
    private String clientOrderId;

    @Column(nullable = false)
    private String exchange;

    @Column(nullable = false)
    private String symbol;

    @Column(nullable = false)
    private String side;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal quantity;

    @Column(name = "order_type", nullable = false)
    private String orderType;

    @Column(precision = 30, scale = 10)
    private BigDecimal price;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    protected OrderCommandEntity() {}

    public OrderCommandEntity(String commandId, String accountId, String proposalId, String clientOrderId,
                               String exchange, String symbol, String side, String action,
                               BigDecimal quantity, String orderType, long createdAt) {
        this.commandId = commandId;
        this.accountId = accountId;
        this.proposalId = proposalId;
        this.clientOrderId = clientOrderId;
        this.exchange = exchange;
        this.symbol = symbol;
        this.side = side;
        this.action = action;
        this.quantity = quantity;
        this.orderType = orderType;
        this.status = "PENDING";
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getCommandId() { return commandId; }
    public String getAccountId() { return accountId; }
    public String getProposalId() { return proposalId; }
    public String getClientOrderId() { return clientOrderId; }
    public String getExchange() { return exchange; }
    public String getSymbol() { return symbol; }
    public String getSide() { return side; }
    public String getAction() { return action; }
    public BigDecimal getQuantity() { return quantity; }
    public String getOrderType() { return orderType; }
    public String getStatus() { return status; }
    public long getCreatedAt() { return createdAt; }

    public void setStatus(String status) { this.status = status; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }
}
