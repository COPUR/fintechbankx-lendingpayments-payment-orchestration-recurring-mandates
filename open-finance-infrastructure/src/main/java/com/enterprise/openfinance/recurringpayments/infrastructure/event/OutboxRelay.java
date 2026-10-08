package com.enterprise.openfinance.recurringpayments.infrastructure.event;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import io.micrometer.core.instrument.MeterRegistry;
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

/**
 * Relays committed outbox rows to Kafka in insertion order.
 *
 * One replica relays at a time (PostgreSQL advisory lock), so the service can
 * scale out without reordering a mandate's events. Consumers de-duplicate on
 * eventId, which makes the at-least-once delivery safe.
 *
 * A failed send is handled by what failed (ADR-021 decision 4, adr-runbooks
 * #10 e6dd76a):
 * <ul>
 *   <li>{@link FailureKind#PAYLOAD} (RecordTooLarge, Serialization,
 *   InvalidTopic: this record can never be sent): the row is parked at once
 *   with a recorded reason and the batch continues with other mandates.</li>
 *   <li>{@link FailureKind#OTHER}, every other error (retriable broker or
 *   network errors, the relay's own send timeout, authentication or
 *   authorisation, producer construction failures, anything unclassified):
 *   never parks. The batch stops without marking any row (no park, no
 *   attempt, no last_error) and the relay retries with exponential backoff
 *   (2 s doubling to 5 min, reset by the next successful send). There is no
 *   time-based parking; only an operator parks such a row, by hand and with
 *   a recorded reason (runbook "Parked outbox events", V6 constraint).</li>
 * </ul>
 * A parked row holds back its mandate: later events of the same aggregate are
 * not published (in this run or later ones) until an operator replays or
 * discards the parked row, so a mandate's events never reach consumers out of
 * order. Other mandates continue.
 * Signals (platform Kafka guide 5f7d546): gauge
 * outbox_oldest_pending_age_seconds (a stalled relay), counters
 * outbox_send_failures_total and outbox_parked_events_total, both tagged by
 * exception class only (never ids). Operator parks (runbook SQL) are counted
 * once by the relay under its lock, with exception="OperatorPark".
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
    private final MeterRegistry meters;

    static final String SEND_FAILURES = "outbox.send.failures";
    static final String PARKED_EVENTS = "outbox.parked.events";
    static final String OPERATOR_PARK = "OperatorPark";

    static final Duration BLOCKED_BACKOFF_BASE = Duration.ofSeconds(2);
    static final Duration BLOCKED_BACKOFF_MAX = Duration.ofMinutes(5);
    // Touched only by the scheduler thread.
    private volatile int consecutiveBlocked;
    private volatile Instant blockedUntil = Instant.MIN;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention, MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.meters = meters;
    }

    /**
     * @return number of events published in this run
     */
    public int relayOnce() {
        if (clock.instant().isBefore(blockedUntil)) {
            return 0; // backing off after a failure that was not the record's fault
        }
        Integer published = transactions.execute(status -> {
            if (!outbox.tryRelayLock(RELAY_LOCK_KEY)) {
                return 0;
            }
            countOperatorParks();
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
                    break; // shutdown: nothing is marked, the row goes out next time
                } catch (Exception e) {
                    Instant now = clock.instant();
                    recordSendFailure(e);
                    if (classify(e) == FailureKind.OTHER) {
                        Duration backoff = block(now);
                        log.warn("Outbox relay stopped: {} publishing to {}; no row marked, retrying in {}",
                                rootCause(e).getClass().getSimpleName(), row.getTopic(), backoff, e);
                        break;
                    }
                    row.markFailed(describe(e));
                    row.park(now, "relay: payload error " + describe(e));
                    recordParked(rootCause(e).getClass().getSimpleName());
                    heldBack.add(row.getAggregateId());
                    log.error("Outbox relay parked event {} for {}: {}; its mandate's later events wait until it is"
                            + " replayed or discarded by hand", row.getEventId(), row.getTopic(), row.getLastError(), e);
                }
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    /** Counts each failed send, tagged with the unwrapped exception class only. */
    public void recordSendFailure(Throwable failure) {
        meters.counter(SEND_FAILURES, "exception", rootCause(failure).getClass().getSimpleName()).increment();
    }

    /** Counts a parked row; alert on any increase. */
    public void recordParked(String exceptionClass) {
        meters.counter(PARKED_EVENTS, "exception", exceptionClass).increment();
    }

    /** Operator parks happen in SQL; count each one once (we hold the relay lock, so one replica does). */
    private void countOperatorParks() {
        for (OutboxEventJpaEntity parked : outbox.findUncountedParks()) {
            parked.markParkCounted();
            recordParked(OPERATOR_PARK);
            log.warn("Outbox event {} for {} was parked by an operator", parked.getEventId(), parked.getTopic());
        }
    }

    public int purgePublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(retention)));
        return deleted == null ? 0 : deleted;
    }

    /** True while the relay is backing off after a failure. */
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
    enum FailureKind { PAYLOAD, OTHER }

    static FailureKind classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RecordTooLargeException || cause instanceof SerializationException
                    || cause instanceof InvalidTopicException) {
                return FailureKind.PAYLOAD;
            }
        }
        return FailureKind.OTHER;
    }

    /** The underlying failure, without the future and KafkaTemplate wrappers, for last_error. */
    static String describe(Throwable failure) {
        Throwable cause = rootCause(failure);
        return cause.getMessage() == null
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    /** The failure without the future and KafkaTemplate wrappers. */
    static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException
                || cause instanceof KafkaProducerException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
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
