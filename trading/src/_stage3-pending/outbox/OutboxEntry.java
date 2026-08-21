package com.sonar.trading.outbox;

import jakarta.persistence.*;

/**
 * Persistent outbox row (ADR-0013 transactional outbox pattern).
 * Written in the same DB transaction as the business state change;
 * forwarded to Kafka by {@link OutboxPublisher} (polling mode) or by the
 * Debezium CDC connector (H1, {@code outbox.publisher=cdc}).
 *
 * <p>Header mirror (ADR-0022/0025) lives in discrete columns — Debezium's
 * EventRouter maps a column to a Kafka header but cannot expand a JSON map.
 */
@Entity
@Table(schema = "trading", name = "outbox")
public class OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String topic;

    @Column(name = "partition_key", nullable = false)
    private String partitionKey;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "causation_id")
    private String causationId;

    @Column(name = "account_id")
    private String accountId;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "published_at")
    private Long publishedAt;

    protected OutboxEntry() {}

    public OutboxEntry(String topic, String partitionKey, String payload, long createdAt) {
        this(topic, partitionKey, payload, null, null, null, createdAt);
    }

    public OutboxEntry(String topic, String partitionKey, String payload,
                       String correlationId, String causationId, String accountId, long createdAt) {
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.accountId = accountId;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getTopic() { return topic; }
    public String getPartitionKey() { return partitionKey; }
    public String getPayload() { return payload; }
    public String getCorrelationId() { return correlationId; }
    public String getCausationId() { return causationId; }
    public String getAccountId() { return accountId; }
    public long getCreatedAt() { return createdAt; }
    public Long getPublishedAt() { return publishedAt; }

    public void setPublishedAt(Long publishedAt) { this.publishedAt = publishedAt; }
}
