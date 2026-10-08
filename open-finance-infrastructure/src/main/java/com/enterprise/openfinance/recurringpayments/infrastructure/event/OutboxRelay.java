package com.enterprise.openfinance.recurringpayments.infrastructure.event;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Relays committed outbox rows to Kafka in insertion order.
 *
 * One replica relays at a time (PostgreSQL advisory lock), so the service can
 * scale out without reordering a mandate's events. A failed send stops that
 * mandate's events for this run (later events of the same mandate wait, other
 * mandates continue); after maxAttempts failures the row is parked for an
 * operator. Consumers de-duplicate on eventId, which makes the at-least-once
 * delivery safe.
 */
public class OutboxRelay {

    static final long RELAY_LOCK_KEY = 0x6D616E5F6F7574L; // "man_out"
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration sendTimeout;
    private final Duration retention;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize, int maxAttempts,
                       Duration sendTimeout, Duration retention) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
    }

    /**
     * @return number of events published in this run
     */
    public int relayOnce() {
        Integer published = transactions.execute(status -> {
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return 0;
            }
            List<OutboxEventJpaEntity> batch = outbox.findUnpublishedBatch(batchSize);
            Set<String> blockedAggregates = new java.util.HashSet<>();
            int sent = 0;
            for (OutboxEventJpaEntity row : batch) {
                if (blockedAggregates.contains(row.getAggregateId())) {
                    continue; // keep this mandate's order: an earlier event of it failed in this run
                }
                try {
                    kafka.send(toRecord(row)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    row.markPublished(clock.instant());
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    row.markFailed("interrupted", maxAttempts, clock.instant());
                    break;
                } catch (Exception e) {
                    row.markFailed(e.getClass().getSimpleName(), maxAttempts, clock.instant());
                    if (row.getParkedAt() != null) {
                        log.error("Outbox event {} to {} parked after {} attempts", row.getEventId(), row.getTopic(),
                                row.getAttempts(), e);
                    } else {
                        log.warn("Outbox relay could not publish event {} to {}; will retry", row.getEventId(),
                                row.getTopic(), e);
                        blockedAggregates.add(row.getAggregateId());
                    }
                }
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    public int purgePublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(retention)));
        return deleted == null ? 0 : deleted;
    }

    static ProducerRecord<String, String> toRecord(OutboxEventJpaEntity row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(row.getTopic(), row.getAggregateId(), row.getPayload());
        record.headers().add("eventType", row.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", row.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlationId", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        // Every mandate change starts at the FAPI API, so the correlation id is the interaction id.
        record.headers().add("x-fapi-interaction-id", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        if (row.getTraceparent() != null) {
            record.headers().add("traceparent", row.getTraceparent().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
