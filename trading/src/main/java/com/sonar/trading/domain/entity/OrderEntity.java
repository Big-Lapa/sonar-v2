package com.sonar.trading.domain.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity
@Table(name = "orders", schema = "trading")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_order_id", nullable = false, unique = true)
    private String clientOrderId;

    @Column(name = "command_id", nullable = false)
    private String commandId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "exchange_order_id")
    private String exchangeOrderId;

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

    @Column(nullable = false)
    private String status;

    @Column(name = "filled_quantity", precision = 30, scale = 10)
    private BigDecimal filledQuantity;

    @Column(name = "avg_fill_price", precision = 30, scale = 10)
    private BigDecimal avgFillPrice;

    @Column(precision = 30, scale = 10)
    private BigDecimal fee;

    @Column(name = "error_code")
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "exchange_event_time")
    private Long exchangeEventTime;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    protected OrderEntity() {}

    public OrderEntity(String clientOrderId, String commandId, String accountId,
                        String exchange, String symbol, String side, String action,
                        BigDecimal quantity, long createdAt) {
        this.clientOrderId = clientOrderId;
        this.commandId = commandId;
        this.accountId = accountId;
        this.exchange = exchange;
        this.symbol = symbol;
        this.side = side;
        this.action = action;
        this.quantity = quantity;
        this.status = "SUBMITTED";
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public Long getId() { return id; }
    public String getClientOrderId() { return clientOrderId; }
    public String getCommandId() { return commandId; }
    public String getAccountId() { return accountId; }
    public String getExchangeOrderId() { return exchangeOrderId; }
    public String getExchange() { return exchange; }
    public String getSymbol() { return symbol; }
    public String getSide() { return side; }
    public String getAction() { return action; }
    public BigDecimal getQuantity() { return quantity; }
    public String getStatus() { return status; }
    public BigDecimal getFilledQuantity() { return filledQuantity; }
    public BigDecimal getAvgFillPrice() { return avgFillPrice; }
    public BigDecimal getFee() { return fee; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Long getExchangeEventTime() { return exchangeEventTime; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }

    public void applyResult(String exchangeOrderId, String status,
                             BigDecimal filledQuantity, BigDecimal avgFillPrice,
                             BigDecimal fee, String errorCode, String errorMessage,
                             long eventTime) {
        this.exchangeOrderId = exchangeOrderId;
        this.status = status;
        this.filledQuantity = filledQuantity;
        this.avgFillPrice = avgFillPrice;
        this.fee = fee;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.exchangeEventTime = eventTime;
        this.updatedAt = System.currentTimeMillis();
    }
}
