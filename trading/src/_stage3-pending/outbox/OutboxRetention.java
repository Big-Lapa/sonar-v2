package com.sonar.trading.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

/**
 * H1 (ADR-0013): bounds outbox table growth. In {@code cdc} mode nothing ever stamps
 * {@code published_at} (the Debezium connector streams rows from the WAL), so rows are
 * deleted purely by age. In {@code polling} mode only PUBLISHED rows are deleted — an
 * unpublished row older than the retention window is a publishing outage, not garbage.
 *
 * <p>DELETEs on the outbox do reach the WAL, but the connector's EventRouter drops
 * DELETE ops, so retention never produces Kafka events.
 *
 * <p>Emergency fallback cdc→polling after a CDC period re-publishes the undeleted tail
 * (published_at is NULL there); consumers dedup by envelope.eventId (ADR-0014).
 */
@Component
public class OutboxRetention {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetention.class);

    private final OutboxRepository repo;
    private final String publisherMode;
    private final long retentionHours;

    public OutboxRetention(OutboxRepository repo,
                           @Value("${outbox.publisher:polling}") String publisherMode,
                           @Value("${outbox.retention-hours:24}") long retentionHours) {
        this.repo = repo;
        this.publisherMode = publisherMode;
        this.retentionHours = retentionHours;
    }

    @Scheduled(fixedDelayString = "${outbox.retention-sweep-ms:3600000}", initialDelay = 60_000)
    @Transactional
    public void sweep() {
        long cutoff = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(retentionHours);
        int deleted = "cdc".equalsIgnoreCase(publisherMode)
                ? repo.deleteOlderThan(cutoff)
                : repo.deletePublishedOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Outbox retention: removed {} rows older than {}h (mode={})",
                    deleted, retentionHours, publisherMode);
        }
    }
}
