package com.sonar.trading.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Single row (id=1) — `strategy` is stateless, its phase lives here (5). */
@Entity
@Table(name = "strategy_state", schema = "trading")
public class StrategyStateEntity {

    public static final long SINGLETON_ID = 1L;
    public static final String IDLE = "IDLE";
    public static final String PROMPT_PENDING = "PROMPT_PENDING";
    public static final String SEARCHING = "SEARCHING";
    public static final String PROPOSALS_PENDING = "PROPOSALS_PENDING";

    @Id
    private Long id = SINGLETON_ID;

    @Column(nullable = false, length = 30)
    private String phase = IDLE;

    @Column(name = "prompt_message_id")
    private Long promptMessageId;

    @Column(name = "prompt_sent_at")
    private Long promptSentAt;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    protected StrategyStateEntity() {}

    public static StrategyStateEntity idle() {
        StrategyStateEntity e = new StrategyStateEntity();
        e.updatedAt = System.currentTimeMillis();
        return e;
    }

    public Long getId() { return id; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public Long getPromptMessageId() { return promptMessageId; }
    public void setPromptMessageId(Long promptMessageId) { this.promptMessageId = promptMessageId; }
    public Long getPromptSentAt() { return promptSentAt; }
    public void setPromptSentAt(Long promptSentAt) { this.promptSentAt = promptSentAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
