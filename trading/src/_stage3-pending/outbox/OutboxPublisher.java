package com.sonar.trading.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Polls the outbox table every 500 ms and forwards unpublished entries to Kafka in id order.
 * Stops on first failure so entries are always published in order (no gaps). Marks each entry
 * as published atomically after the Kafka send completes (blocking get with 5 s timeout).
 *
 * <p>Header mirror (ADR-0022/0025): the discrete {@code correlation_id}/{@code causation_id}/
 * {@code account_id} columns are attached to the producer record as Kafka message headers.
 *
 * <p>H1 (ADR-0013): active only in {@code outbox.publisher=polling} mode (the default). In
 * {@code cdc} mode the Debezium connector streams the same rows from the WAL and this bean is
 * not created — {@code published_at} then stays NULL by design.
 *
 * <p>Exposes {@code trading_outbox_lag} gauge so the dashboard can alert on publishing
 * falling behind write throughput.
 */
@Component
@ConditionalOnProperty(name = "outbox.publisher", havingValue = "polling", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository repo;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * Backing value for the {@code trading_outbox_lag} gauge, registered ONCE against this
     * strong-referenced field: the old per-tick {@code meterRegistry.gauge(name, value)} bound
     * the gauge to the first autoboxed Long — the metric froze and NaN'd after GC, leaving the
     * ADR-0013 lag alert blind (same Micrometer weak-reference class as the pnl-gauges bug).
     */
    private final java.util.concurrent.atomic.AtomicLong outboxLag = new java.util.concurrent.atomic.AtomicLong();

    public OutboxPublisher(OutboxRepository repo,
                           KafkaTemplate<String, String> kafkaTemplate,
                           MeterRegistry meterRegistry) {
        this.repo = repo;
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("trading_outbox_lag", outboxLag);
    }

    @Scheduled(fixedDelay = 500)
    public void pollAndPublish() {
        List<OutboxEntry> entries = repo.findUnpublishedOrderById(PageRequest.of(0, BATCH_SIZE));
        for (OutboxEntry entry : entries) {
            try {
                kafkaTemplate.send(toRecord(entry)).get(5, TimeUnit.SECONDS);
                markPublished(entry);
            } catch (Exception ex) {
                log.error("Outbox publish failed id={} topic={}: {}", entry.getId(), entry.getTopic(), ex.getMessage());
                break; // preserve order — do not skip ahead
            }
        }
        try {
            outboxLag.set(repo.countUnpublished());
        } catch (Exception ignored) {
            // metrics must never block the outbox loop
        }
    }

    ProducerRecord<String, String> toRecord(OutboxEntry entry) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(entry.getTopic(), entry.getPartitionKey(), entry.getPayload());
        addHeader(record, "correlationId", entry.getCorrelationId());
        addHeader(record, "causationId", entry.getCausationId());
        addHeader(record, "accountId", entry.getAccountId());
        return record;
    }

    private static void addHeader(ProducerRecord<String, String> record, String key, String value) {
        if (value != null && !value.isBlank()) {
            record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Transactional
    public void markPublished(OutboxEntry entry) {
        entry.setPublishedAt(System.currentTimeMillis());
        repo.save(entry);
    }
}
