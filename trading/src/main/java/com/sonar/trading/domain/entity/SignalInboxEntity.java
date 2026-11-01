package com.sonar.trading.domain.entity;

import jakarta.persistence.*;

/**
 * Second idempotency layer under {@code proposals.status}: a retried confirm for one
 * account in the fan-out is keyed by (idempotency_key=proposal_id, account_id) so it doesn't
 * double-fire while other accounts are still pending.
 */
@Entity
@Table(name = "signal_inbox", schema = "trading",
       uniqueConstraints = @UniqueConstraint(name = "uq_signal_inbox_key",
               columnNames = {"idempotency_key", "account_id"}))
public class SignalInboxEntity {

    public static final String PROCESSING = "PROCESSING";
    public static final String DONE = "DONE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    @Column(name = "signal_id", nullable = false)
    private String signalId;

    @Column(name = "received_at", nullable = false)
    private long receivedAt;

    /** PROCESSING until the pipeline finishes, then DONE. A stale PROCESSING row = a crashed attempt. */
    @Column(name = "status", nullable = false)
    private String status = PROCESSING;

    @Column(name = "processed_at")
    private Long processedAt;

    protected SignalInboxEntity() {}

    public SignalInboxEntity(String idempotencyKey, String accountId, String signalId, long receivedAt) {
        this.idempotencyKey = idempotencyKey;
        this.accountId = accountId;
        this.signalId = signalId;
        this.receivedAt = receivedAt;
        this.status = PROCESSING;
    }

    public Long getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getAccountId() { return accountId; }
    public String getSignalId() { return signalId; }
    public long getReceivedAt() { return receivedAt; }
    public void setReceivedAt(long receivedAt) { this.receivedAt = receivedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getProcessedAt() { return processedAt; }
    public void setProcessedAt(Long processedAt) { this.processedAt = processedAt; }
}
