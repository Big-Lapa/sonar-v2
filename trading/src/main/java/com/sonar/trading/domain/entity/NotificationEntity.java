package com.sonar.trading.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Operator-facing event queued by the minute poller (4.7/4.8). Trading has no way to push into
 * Telegram (that lives in `strategy`) and `strategy` has no incoming REST — so events land here
 * and `strategy` pulls them with its own minute tick via {@code GET /notifications?after=}.
 */
@Entity
@Table(name = "notifications", schema = "trading")
public class NotificationEntity {

    public static final String POSITION_CLOSED = "POSITION_CLOSED";
    public static final String MISSING_STOP = "MISSING_STOP";
    public static final String QTY_MISMATCH = "QTY_MISMATCH";
    public static final String HOLD_REMINDER = "HOLD_REMINDER";
    /** 10, грабля #2: equity dropped >50% in one tick — likely a demo-balance reset, not real loss. */
    public static final String ANOMALY_EQUITY = "ANOMALY_EQUITY";
    /** Poller found a position open on the exchange with no matching DB row (opened directly on
     *  the venue) and started tracking it — fires once, at discovery, not on every tick after. */
    public static final String POSITION_ADOPTED = "POSITION_ADOPTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String kind;

    @Column(name = "account_id", length = 100)
    private String accountId;

    @Column(length = 20)
    private String symbol;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    protected NotificationEntity() {}

    public NotificationEntity(String kind, String accountId, String symbol, String message) {
        this.kind = kind;
        this.accountId = accountId;
        this.symbol = symbol;
        this.message = message;
        this.createdAt = System.currentTimeMillis();
    }

    public Long getId() { return id; }
    public String getKind() { return kind; }
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public String getMessage() { return message; }
    public long getCreatedAt() { return createdAt; }
}
