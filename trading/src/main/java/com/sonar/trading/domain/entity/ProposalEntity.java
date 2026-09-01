package com.sonar.trading.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A candidate trade awaiting human confirmation in Telegram (5). LONG-only in v1. */
@Entity
@Table(name = "proposals", schema = "trading")
public class ProposalEntity {

    @Id
    @Column(name = "proposal_id", length = 40)
    private String proposalId;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 10)
    private String side;

    @Column(name = "entry_price", nullable = false, length = 30)
    private String entryPrice;

    @Column(name = "sl_price", nullable = false, length = 30)
    private String slPrice;

    @Column(name = "tp_price", nullable = false, length = 30)
    private String tpPrice;

    @Column(columnDefinition = "text")
    private String rationale;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "decided_at")
    private Long decidedAt;

    @Column(name = "tg_message_id")
    private Long tgMessageId;

    /** Manual multi-exchange management: which single account this proposal executes on.
     *  Null means untargeted/legacy (fans out across every tradable account, pre-manual-mode). */
    @Column(name = "account_id", length = 100)
    private String accountId;

    protected ProposalEntity() {}

    public static ProposalEntity pending(String proposalId, String symbol, String side,
                                          String entryPrice, String slPrice, String tpPrice,
                                          String rationale, long createdAt, String accountId) {
        ProposalEntity p = new ProposalEntity();
        p.proposalId = proposalId;
        p.symbol = symbol;
        p.side = side;
        p.entryPrice = entryPrice;
        p.slPrice = slPrice;
        p.tpPrice = tpPrice;
        p.rationale = rationale;
        p.status = "PENDING";
        p.createdAt = createdAt;
        p.accountId = accountId;
        return p;
    }

    public String getProposalId() { return proposalId; }
    public String getSymbol() { return symbol; }
    public String getSide() { return side; }
    public String getEntryPrice() { return entryPrice; }
    public String getSlPrice() { return slPrice; }
    public String getTpPrice() { return tpPrice; }
    public String getRationale() { return rationale; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public Long getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Long decidedAt) { this.decidedAt = decidedAt; }
    public Long getTgMessageId() { return tgMessageId; }
    public void setTgMessageId(Long tgMessageId) { this.tgMessageId = tgMessageId; }
    public String getAccountId() { return accountId; }
}
