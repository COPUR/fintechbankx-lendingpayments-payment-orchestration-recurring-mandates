package com.enterprise.openfinance.recurringpayments.infrastructure.event;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Relays committed outbox rows to Kafka in insertion order.
 *
 * One replica relays at a time (PostgreSQL advisory lock), so the service can
 * scale out without reordering a mandate's events. Consumers de-duplicate on
 * eventId, which makes the at-least-once delivery safe.
 *
 * A failed send is handled by what failed:
 * <ul>
 *   <li>{@link FailureKind#RETRIABLE} (a Kafka {@link RetriableException}
 *   anywhere in the cause chain, e.g. TimeoutException, NotEnoughReplicas,
 *   NetworkException, or the relay's own send timeout): the batch stops and the
 *   row is retried next run. It parks only after failing continuously for
 *   longer than {@code retryableParkAfter} (default 24 h, from
 *   first_failed_at), so a broker or egress outage only delays events.</li>
 *   <li>{@link FailureKind#PAYLOAD} (RecordTooLarge, Serialization,
 *   InvalidTopic: this record can never be sent): the row is parked at once
 *   and the batch continues with other mandates.</li>
 *   <li>{@link FailureKind#BLOCKING} (authentication or authorisation such as
 *   SaslAuthentication or TopicAuthorization, producer construction failures,
 *   anything unclassified): a problem of the relay, not of the row. The batch
 *   stops without marking any row (no park, no attempt, no last_error); the
 *   relay retries with exponential backoff (2 s doubling to 5 min, reset by
 *   the next successful send) and reports itself blocked through the
 *   outbox_relay_blocked gauge, which is the alert.</li>
 * </ul>
 * A parked row holds back its mandate: later events of the same aggregate are
 * not published (in this run or later ones) until an operator replays or
 * discards the parked row (runbook "Parked outbox events"), so a mandate's
 * events never reach consumers out of order. Other mandates continue.
 * Watch outbox_oldest_pending_age_seconds, outbox_parked_events and
 * outbox_relay_blocked.
 *
 * Policy: ADR-021 decision 4 (adr-runbooks #10, 421f7b5): payload errors park
 * the row and continue; authorisation and unclassified errors stop the batch
 * without marking any row, retry with backoff and alert; retriable errors
 * park only after 24 h of continuous failure.
 */
public class OutboxRelay {

    static final long RELAY_LOCK_KEY = 0x6D616E5F6F7574L; // "man_out"
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final Duration retryableParkAfter;

    static final Duration BLOCKED_BACKOFF_BASE = Duration.ofSeconds(2);
    static final Duration BLOCKED_BACKOFF_MAX = Duration.ofMinutes(5);
    // Touched only by the scheduler thread; volatile for the gauge.
    private volatile int consecutiveBlocked;
    private volatile Instant blockedUntil = Instant.MIN;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention, Duration retryableParkAfter) {
        if (retryableParkAfter == null || retryableParkAfter.isZero() || retryableParkAfter.isNegative()) {
            throw new IllegalArgumentException("mandates.outbox.relay.retryable-park-after must be positive");
        }
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.retryableParkAfter = retryableParkAfter;
    }

    /**
     * @return number of events published in this run
     */
    public int relayOnce() {
        if (clock.instant().isBefore(blockedUntil)) {
            return 0; // backing off after an authorisation or unclassified failure
        }
        Integer published = transactions.execute(status -> {
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return 0;
            }
            List<OutboxEventJpaEntity> batch = outbox.findUnpublishedBatch(batchSize);
            Set<String> heldBack = new HashSet<>();
            int sent = 0;
            for (OutboxEventJpaEntity row : batch) {
                if (heldBack.contains(row.getAggregateId())) {
                    continue; // an earlier event of this mandate was parked in this run
                }
                try {
                    kafka.send(toRecord(row)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
                    row.markPublished(clock.instant());
                    unblock();
                    sent++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    row.markFailed("interrupted", clock.instant());
                    break;
                } catch (Exception e) {
                    Instant now = clock.instant();
                    FailureKind kind = classify(e);
                    if (kind == FailureKind.BLOCKING) {
                        Duration backoff = block(now);
                        log.error("Outbox relay blocked: {} publishing to {} is not a problem of the event"
                                + " (authentication, authorisation or producer set-up); no row marked, retrying in {}",
                                e.getClass().getSimpleName(), row.getTopic(), backoff, e);
                        break;
                    }
                    row.markFailed(describe(e), now);
                    if (kind == FailureKind.RETRIABLE && !now.isAfter(row.getFirstFailedAt().plus(retryableParkAfter))) {
                        log.warn("Outbox relay could not publish event {} to {} (attempt {}, failing since {}); will retry",
                                row.getEventId(), row.getTopic(), row.getAttempts(), row.getFirstFailedAt(), e);
                        break;
                    }
                    row.park(now);
                    heldBack.add(row.getAggregateId());
                    log.error("Outbox relay parked event {} for {} after {} attempt(s): {}; its mandate's later events"
                            + " wait until it is replayed by hand", row.getEventId(), row.getTopic(), row.getAttempts(),
                            row.getLastError(), e);
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

    /** True while the relay is backing off after an authorisation or unclassified failure (the alert). */
    public boolean blocked() {
        return consecutiveBlocked > 0;
    }

    private Duration block(Instant now) {
        int failures = ++consecutiveBlocked;
        Duration backoff = BLOCKED_BACKOFF_BASE.multipliedBy(1L << Math.min(failures - 1, 20));
        if (backoff.compareTo(BLOCKED_BACKOFF_MAX) > 0) {
            backoff = BLOCKED_BACKOFF_MAX;
        }
        blockedUntil = now.plus(backoff);
        return backoff;
    }

    private void unblock() {
        consecutiveBlocked = 0;
        blockedUntil = Instant.MIN;
    }

    /** How the relay treats a failed send; see the class comment. */
    enum FailureKind { RETRIABLE, PAYLOAD, BLOCKING }

    static FailureKind classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RecordTooLargeException || cause instanceof SerializationException
                    || cause instanceof InvalidTopicException) {
                return FailureKind.PAYLOAD;
            }
            if (cause instanceof RetriableException || cause instanceof TimeoutException) {
                return FailureKind.RETRIABLE;
            }
        }
        return FailureKind.BLOCKING;
    }

    /** The underlying failure, without the future and KafkaTemplate wrappers, for last_error. */
    static String describe(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException
                || cause instanceof KafkaProducerException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
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
