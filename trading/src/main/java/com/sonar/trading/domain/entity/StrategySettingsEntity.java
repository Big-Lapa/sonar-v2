package com.sonar.trading.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Single row (id=1), strategy-wide gates/thresholds (4.1). Owned by `strategy`; trading only reads it. */
@Entity
@Table(name = "strategy_settings", schema = "trading")
public class StrategySettingsEntity {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    @Column(name = "min_sl_distance_pct", nullable = false, length = 30)
    private String minSlDistancePct = "0.015";

    @Column(name = "min_sl_cost_mult", nullable = false, length = 30)
    private String minSlCostMult = "3";

    @Column(name = "max_sl_distance_pct", nullable = false, length = 30)
    private String maxSlDistancePct = "0.12";

    @Column(name = "min_rr", nullable = false, length = 30)
    private String minRr = "1.8";

    @Column(name = "min_24h_volume_usd", nullable = false, length = 30)
    private String min24hVolumeUsd = "20000000";

    @Column(name = "max_move_24h_pct", nullable = false, length = 30)
    private String maxMove24hPct = "0.05";

    @Column(name = "max_move_7d_pct", nullable = false, length = 30)
    private String maxMove7dPct = "0.15";

    @Column(name = "max_candidates", nullable = false)
    private int maxCandidates = 3;

    @Column(name = "llm_model", nullable = false, length = 60)
    private String llmModel = "claude-sonnet-5";

    @Column(name = "always_draw_tv", nullable = false)
    private boolean alwaysDrawTv = false;

    @Column(name = "proposal_ttl_ms", nullable = false)
    private long proposalTtlMs = 600000;

    @Column(name = "hold_reminder_days", nullable = false)
    private int holdReminderDays = 7;

    @Column(name = "search_cron_hours", nullable = false, length = 60)
    private String searchCronHours = "*";

    @Version
    @Column(nullable = false)
    private int version = 1;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt;

    protected StrategySettingsEntity() {}

    public static StrategySettingsEntity defaults() {
        StrategySettingsEntity e = new StrategySettingsEntity();
        e.updatedAt = System.currentTimeMillis();
        return e;
    }

    public Long getId() { return id; }
    public String getMinSlDistancePct() { return minSlDistancePct; }
    public void setMinSlDistancePct(String v) { this.minSlDistancePct = v; }
    public String getMinSlCostMult() { return minSlCostMult; }
    public void setMinSlCostMult(String v) { this.minSlCostMult = v; }
    public String getMaxSlDistancePct() { return maxSlDistancePct; }
    public void setMaxSlDistancePct(String v) { this.maxSlDistancePct = v; }
    public String getMinRr() { return minRr; }
    public void setMinRr(String v) { this.minRr = v; }
    public String getMin24hVolumeUsd() { return min24hVolumeUsd; }
    public void setMin24hVolumeUsd(String v) { this.min24hVolumeUsd = v; }
    public String getMaxMove24hPct() { return maxMove24hPct; }
    public void setMaxMove24hPct(String v) { this.maxMove24hPct = v; }
    public String getMaxMove7dPct() { return maxMove7dPct; }
    public void setMaxMove7dPct(String v) { this.maxMove7dPct = v; }
    public int getMaxCandidates() { return maxCandidates; }
    public void setMaxCandidates(int v) { this.maxCandidates = v; }
    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String v) { this.llmModel = v; }
    public boolean isAlwaysDrawTv() { return alwaysDrawTv; }
    public void setAlwaysDrawTv(boolean v) { this.alwaysDrawTv = v; }
    public long getProposalTtlMs() { return proposalTtlMs; }
    public void setProposalTtlMs(long v) { this.proposalTtlMs = v; }
    public int getHoldReminderDays() { return holdReminderDays; }
    public void setHoldReminderDays(int v) { this.holdReminderDays = v; }
    public String getSearchCronHours() { return searchCronHours; }
    public void setSearchCronHours(String v) { this.searchCronHours = v; }
    public int getVersion() { return version; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long v) { this.updatedAt = v; }
}
