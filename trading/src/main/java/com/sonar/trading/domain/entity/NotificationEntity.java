package com.sonar.trading.domain.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonar.trading.i18n.Msg;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Operator-facing event queued by the minute poller. Trading has no way to push into
 * Telegram (that lives in `strategy`) and `strategy` has no incoming REST — so events land here
 * and `strategy` pulls them with its own minute tick via {@code GET /notifications?after=}.
 */
@Entity
@Table(name = "notifications", schema = "trading")
public class NotificationEntity {
    private static final Logger log = LoggerFactory.getLogger(NotificationEntity.class);
    private static final ObjectMapper JSON = new ObjectMapper();


    public static final String POSITION_CLOSED = "POSITION_CLOSED";
    public static final String MISSING_STOP = "MISSING_STOP";
    public static final String QTY_MISMATCH = "QTY_MISMATCH";
    public static final String HOLD_REMINDER = "HOLD_REMINDER";
    /** 10, pitfall #2: equity dropped >50% in one tick — likely a demo-balance reset, not real loss. */
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

    /** Stable {@link com.sonar.trading.i18n.MessageCode} name; null for rows written before V7. */
    @Column(length = 60)
    private String code;

    @JsonIgnore
    @Column(name = "params", columnDefinition = "text")
    private String paramsJson;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    protected NotificationEntity() {}

    public NotificationEntity(String kind, String accountId, String symbol, Msg msg) {
        this.kind = kind;
        this.accountId = accountId;
        this.symbol = symbol;
        this.message = msg.text();
        this.code = msg.code().name();
        this.paramsJson = writeParams(msg.params());
        this.createdAt = System.currentTimeMillis();
    }

    private static String writeParams(Map<String, String> params) {
        try {
            return JSON.writeValueAsString(params);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Long getId() { return id; }
    public String getKind() { return kind; }
    public String getAccountId() { return accountId; }
    public String getSymbol() { return symbol; }
    public String getMessage() { return message; }
    public String getCode() { return code; }

    public Map<String, String> getParams() {
        if (paramsJson == null) return Map.of();
        try {
            return JSON.readValue(paramsJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("notification {} has unreadable params, rendering without them: {}", id, e.toString());
            return Map.of();
        }
    }
    public long getCreatedAt() { return createdAt; }
}
