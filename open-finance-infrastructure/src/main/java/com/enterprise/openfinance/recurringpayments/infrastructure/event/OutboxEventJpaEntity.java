package com.enterprise.openfinance.recurringpayments.infrastructure.event;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Row of sc_pay_recurring_mandates.mandate_outbox_event: one envelope waiting to be
 * relayed to Kafka. Written in the mandate's transaction. A parked row
 * (parked_at set) is skipped by the relay until an operator replays it; see
 * OutboxRelay for when a row is parked.
 */
@Entity
@Table(name = "mandate_outbox_event")
public class OutboxEventJpaEntity {

    static final int MAX_ERROR_LENGTH = 512;

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 64, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64, updatable = false)
    private String aggregateId;

    @Column(name = "aggregate_version", nullable = false, updatable = false)
    private long aggregateVersion;

    @Column(name = "event_type", nullable = false, length = 128, updatable = false)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 249, updatable = false)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "correlation_id", nullable = false, length = 128, updatable = false)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "traceparent", length = 64, updatable = false)
    private String traceparent;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "parked_at")
    private Instant parkedAt;

    /** First failed send; the retryable-failure ceiling is measured from here. */
    @Column(name = "first_failed_at")
    private Instant firstFailedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    protected OutboxEventJpaEntity() {
    }

    public OutboxEventJpaEntity(UUID eventId, String aggregateType, String aggregateId, long aggregateVersion,
                                String eventType, String topic, String payload, String correlationId,
                                Instant occurredAt, String traceparent) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.aggregateVersion = aggregateVersion;
        this.eventType = eventType;
        this.topic = topic;
        this.payload = payload;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
        this.traceparent = traceparent;
    }

    public UUID getEventId() { return eventId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public long getAggregateVersion() { return aggregateVersion; }
    public String getEventType() { return eventType; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public String getCorrelationId() { return correlationId; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getTraceparent() { return traceparent; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getParkedAt() { return parkedAt; }
    public Instant getFirstFailedAt() { return firstFailedAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }

    void markPublished(Instant at) {
        this.publishedAt = at;
        this.attempts++;
        this.lastError = null;
    }

    /** Records a failed send; the first one starts the retryable-failure clock. */
    void markFailed(String error, Instant at) {
        if (firstFailedAt == null) {
            this.firstFailedAt = at;
        }
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
    }

    /** Takes the row out of the relay's queue until an operator replays it. */
    void park(Instant at) {
        this.parkedAt = at;
    }
}
