package com.sonar.trading.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonar.trading.messaging.dto.EventEnvelope;
import org.springframework.stereotype.Component;

/**
 * Serialises an {@link EventEnvelope} to JSON and persists it as an {@link OutboxEntry}
 * within the current transaction. Must only be called inside an active transaction so the
 * outbox write and the business state change are atomic.
 *
 * <p>The Kafka header mirror — {@code correlationId}/{@code causationId} (ADR-0022) and,
 * when the event belongs to a trading account, {@code accountId} (ADR-0025) — is stored in
 * discrete columns so both the polling {@link OutboxPublisher} and the Debezium EventRouter
 * (H1, ADR-0013) can attach them to the produced record.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repo;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository repo, ObjectMapper objectMapper) {
        this.repo = repo;
        this.objectMapper = objectMapper;
    }

    public void write(String topic, String partitionKey, EventEnvelope envelope) {
        write(topic, partitionKey, envelope, null);
    }

    public void write(String topic, String partitionKey, EventEnvelope envelope, String accountId) {
        try {
            String json = objectMapper.writeValueAsString(envelope);
            repo.save(new OutboxEntry(topic, partitionKey, json,
                    envelope.correlationId(), envelope.causationId(), accountId,
                    System.currentTimeMillis()));
        } catch (Exception e) {
            throw new RuntimeException("Outbox write failed for topic=" + topic, e);
        }
    }
}
