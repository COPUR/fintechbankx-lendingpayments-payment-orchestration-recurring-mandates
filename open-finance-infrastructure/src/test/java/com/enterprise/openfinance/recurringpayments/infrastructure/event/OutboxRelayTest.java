package com.enterprise.openfinance.recurringpayments.infrastructure.event;

import com.enterprise.openfinance.recurringpayments.domain.event.MandateEvent;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateRevoked;
import com.enterprise.openfinance.recurringpayments.infrastructure.observability.CorrelationIdFilter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-02-09T10:00:00Z");
    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final MandateEventEnvelopeFactory envelopes = new MandateEventEnvelopeFactory(JsonMapper.builder().build());

    OutboxRelayTest() {
        when(transactions.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void publishesPendingRowsWithKeyAndHeaders() {
        OutboxEventJpaEntity row = row("CONS-1");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findUnpublishedBatch(100)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

        assertThat(relay().relayOnce()).isEqualTo(1);

        assertThat(row.getPublishedAt()).isEqualTo(NOW);
        assertThat(row.getAttempts()).isEqualTo(1);
        ProducerRecord<String, String> record = OutboxRelay.toRecord(row);
        assertThat(record.key()).isEqualTo("CONS-1");
        assertThat(record.topic()).isEqualTo("evt.pay.mandate.revoked.v1");
        assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .isEqualTo("Payments.Mandate.Revoked.v1");
        assertThat(new String(record.headers().lastHeader("x-fapi-interaction-id").value(), StandardCharsets.UTF_8))
                .isEqualTo("ix-relay");
        assertThat(record.headers().lastHeader("traceparent")).isNull();

        OutboxEventJpaEntity traced = envelopes.toOutboxRow(new MandateRevoked(UUID.randomUUID(), "CONS-1", 1L, NOW,
                "TPP-001", "x"), "ix-relay", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        assertThat(new String(OutboxRelay.toRecord(traced).headers().lastHeader("traceparent").value(), StandardCharsets.UTF_8))
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
    }

    @Test
    void doesNothingWhenAnotherReplicaHoldsTheRelayLock() {
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(false);

        assertThat(relay().relayOnce()).isZero();
        verify(outbox, never()).findUnpublishedBatch(100);
    }

    @Test
    void retryableFailuresStopTheBatchAndNeverCountTowardParking() {
        OutboxEventJpaEntity stuck = row("CONS-1");
        OutboxEventJpaEntity later = row("CONS-2");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findUnpublishedBatch(100)).thenReturn(List.of(stuck, later));
        List<RuntimeException> outages = List.of(
                new org.apache.kafka.common.errors.TimeoutException("Expiring 1 record(s)"),
                new org.apache.kafka.common.errors.NotEnoughReplicasException("2 of 3 in sync"),
                new org.apache.kafka.common.errors.NetworkException("broker unavailable"),
                new org.springframework.kafka.core.KafkaProducerException(null, "send failed",
                        new org.apache.kafka.common.errors.TimeoutException("metadata")));
        MutableClock clock = new MutableClock(NOW);
        OutboxRelay relay = relay(clock, Duration.ofHours(24));

        for (int run = 0; run < 200; run++) {
            when(kafka.send(any(ProducerRecord.class)))
                    .thenReturn(CompletableFuture.failedFuture(outages.get(run % outages.size())));
            assertThat(relay.relayOnce()).isZero();
            clock.advance(Duration.ofMinutes(5)); // 200 runs = 16 h 40 min of outage
        }

        assertThat(stuck.getParkedAt()).as("200 retryable failures do not park").isNull();
        assertThat(stuck.getAttempts()).isEqualTo(200);
        assertThat(stuck.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(stuck.getLastError()).startsWith("TimeoutException: metadata");
        assertThat(later.getAttempts()).as("the batch stops at the first retryable failure").isZero();
        verify(kafka, org.mockito.Mockito.times(200)).send(any(ProducerRecord.class));
    }

    @Test
    void theRelaysOwnSendTimeoutIsRetryable() {
        OutboxEventJpaEntity row = row("CONS-1");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findUnpublishedBatch(100)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>()); // never completes

        assertThat(relay(new MutableClock(NOW), Duration.ofHours(24)).relayOnce()).isZero();

        assertThat(row.getParkedAt()).isNull();
        assertThat(row.getLastError()).startsWith("TimeoutException");
    }

    @Test
    void aRetryableFailureParksOnlyAfter24HoursOfContinuousFailure() {
        OutboxEventJpaEntity stuck = row("CONS-1");
        OutboxEventJpaEntity other = row("CONS-2");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findUnpublishedBatch(100)).thenReturn(List.of(stuck, other));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            return record.value().equals(stuck.getPayload())
                    ? CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("expired"))
                    : CompletableFuture.completedFuture(null);
        });
        MutableClock clock = new MutableClock(NOW);
        OutboxRelay relay = relay(clock, Duration.ofHours(24));

        relay.relayOnce();
        clock.advance(Duration.ofHours(24));
        relay.relayOnce();
        assertThat(stuck.getParkedAt()).as("exactly 24 h is still within the ceiling").isNull();
        assertThat(other.getPublishedAt()).isNull();

        clock.advance(Duration.ofSeconds(1));
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(stuck.getParkedAt()).isEqualTo(NOW.plus(Duration.ofHours(24)).plusSeconds(1));
        assertThat(stuck.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(stuck.getAttempts()).isEqualTo(3);
        assertThat(other.getPublishedAt()).as("parking unblocks the rows behind it").isNotNull();
    }

    @Test
    void payloadFailuresParkAtOnceAndHoldBackOnlyTheirMandate() {
        List<RuntimeException> payload = List.of(
                new org.apache.kafka.common.errors.RecordTooLargeException("The message is 2000000 bytes"),
                new org.apache.kafka.common.errors.SerializationException("bad payload"),
                new org.apache.kafka.common.errors.InvalidTopicException("bad topic"));
        for (RuntimeException failure : payload) {
            OutboxEventJpaEntity poison = row("CONS-1");
            OutboxEventJpaEntity sameMandateLater = row("CONS-1");
            OutboxEventJpaEntity otherMandate = row("CONS-2");
            when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
            when(outbox.findUnpublishedBatch(100)).thenReturn(List.of(poison, sameMandateLater, otherMandate));
            when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
                ProducerRecord<String, String> record = invocation.getArgument(0);
                return record.value().equals(poison.getPayload())
                        ? CompletableFuture.failedFuture(failure)
                        : CompletableFuture.completedFuture(null);
            });

            assertThat(relay(new MutableClock(NOW), Duration.ofHours(24)).relayOnce()).isEqualTo(1);

            String kind = failure.getClass().getSimpleName();
            assertThat(poison.getParkedAt()).as(kind).isEqualTo(NOW);
            assertThat(poison.getAttempts()).as(kind).isEqualTo(1);
            assertThat(poison.getLastError()).startsWith(kind);
            assertThat(sameMandateLater.getPublishedAt()).as("%s: a parked event holds back its mandate", kind).isNull();
            assertThat(sameMandateLater.getAttempts()).isZero();
            assertThat(otherMandate.getPublishedAt()).as(kind).isEqualTo(NOW);
            assertThat(OutboxRelay.classify(failure)).isEqualTo(OutboxRelay.FailureKind.PAYLOAD);
        }
    }

    @Test
    void authAndUnclassifiedFailuresStopTheBatchWithoutParkingOrCountingAttempts() {
        List<RuntimeException> blocking = List.of(
                new org.apache.kafka.common.errors.SaslAuthenticationException("IAM auth failed"),
                new org.apache.kafka.common.errors.TopicAuthorizationException(java.util.Set.of("evt.pay.mandate.revoked.v1")),
                new org.apache.kafka.common.KafkaException("Failed to construct kafka producer"),
                new IllegalStateException("anything else"));
        for (RuntimeException failure : blocking) {
            OutboxEventJpaEntity stuck = row("CONS-1");
            OutboxEventJpaEntity otherMandate = row("CONS-2");
            when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
            when(outbox.findUnpublishedBatch(100)).thenReturn(List.of(stuck, otherMandate));
            // Producer construction fails synchronously in send(); the others fail the future.
            if (failure.getMessage().startsWith("Failed to construct")) {
                when(kafka.send(any(ProducerRecord.class))).thenThrow(failure);
            } else {
                when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(failure));
            }
            MutableClock clock = new MutableClock(NOW);
            OutboxRelay relay = relay(clock, Duration.ofHours(24));

            for (int run = 0; run < 3; run++) {
                assertThat(relay.relayOnce()).isZero();
                clock.advance(Duration.ofDays(2)); // well past the retryable ceiling: still never parked
            }

            String kind = failure.getClass().getSimpleName();
            assertThat(stuck.getParkedAt()).as(kind).isNull();
            assertThat(stuck.getAttempts()).as(kind).isZero();
            assertThat(stuck.getFirstFailedAt()).as(kind).isNull();
            assertThat(stuck.getLastError()).as("%s: no row is marked (ADR-021 decision 4)", kind).isNull();
            assertThat(otherMandate.getPublishedAt()).as("%s stops the whole batch", kind).isNull();
            assertThat(relay.blocked()).as(kind).isTrue();
            assertThat(OutboxRelay.classify(failure)).isEqualTo(OutboxRelay.FailureKind.BLOCKING);
        }
    }

    @Test
    void aBlockedRelayBacksOffExponentiallyAndResetsOnSuccess() {
        OutboxEventJpaEntity row = row("CONS-1");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findUnpublishedBatch(100)).thenReturn(List.of(row));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.failedFuture(
                new org.apache.kafka.common.errors.SaslAuthenticationException("IAM auth failed")));
        MutableClock clock = new MutableClock(NOW);
        OutboxRelay relay = relay(clock, Duration.ofHours(24));

        relay.relayOnce();                         // failure 1: back off 2 s
        clock.advance(Duration.ofMillis(1999));
        relay.relayOnce();                         // still backing off: no send
        verify(kafka, org.mockito.Mockito.times(1)).send(any(ProducerRecord.class));
        clock.advance(Duration.ofMillis(1));
        relay.relayOnce();                         // failure 2: back off 4 s
        clock.advance(Duration.ofSeconds(3));
        relay.relayOnce();
        verify(kafka, org.mockito.Mockito.times(2)).send(any(ProducerRecord.class));
        for (int i = 0; i < 20; i++) {             // the delay is capped at 5 min
            clock.advance(Duration.ofMinutes(5));
            relay.relayOnce();
        }
        verify(kafka, org.mockito.Mockito.times(22)).send(any(ProducerRecord.class));
        assertThat(relay.blocked()).isTrue();

        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
        clock.advance(Duration.ofMinutes(5));
        assertThat(relay.relayOnce()).isEqualTo(1);
        assertThat(relay.blocked()).isFalse();
        assertThat(row.getAttempts()).as("blocked runs charged no attempt").isEqualTo(1);
    }

    @Test
    void retriableFailuresAreClassifiedRetriable() {
        assertThat(OutboxRelay.classify(new org.apache.kafka.common.errors.NotEnoughReplicasException("x")))
                .isEqualTo(OutboxRelay.FailureKind.RETRIABLE);
        assertThat(OutboxRelay.classify(new java.util.concurrent.ExecutionException(
                new org.apache.kafka.common.errors.TimeoutException("x")))).isEqualTo(OutboxRelay.FailureKind.RETRIABLE);
        assertThat(OutboxRelay.classify(new java.util.concurrent.TimeoutException()))
                .isEqualTo(OutboxRelay.FailureKind.RETRIABLE);
    }

    @Test
    void interruptionStopsTheBatch() throws Exception {
        OutboxEventJpaEntity row = row("CONS-1");
        when(outbox.tryRelayLock(OutboxRelay.RELAY_LOCK_KEY)).thenReturn(true);
        when(outbox.findUnpublishedBatch(100)).thenReturn(List.of(row, row("CONS-2")));
        CompletableFuture<SendResult<String, String>> interrupted = new CompletableFuture<>() {
            @Override
            public SendResult<String, String> get(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("shutdown");
            }
        };
        when(kafka.send(any(ProducerRecord.class))).thenReturn(interrupted);

        assertThat(relay().relayOnce()).isZero();
        assertThat(row.getLastError()).isEqualTo("interrupted");
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void purgesPublishedRowsOlderThanTheRetention() {
        when(outbox.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(5);

        assertThat(relay().purgePublished()).isEqualTo(5);
    }

    @Test
    void rejectsANonPositiveParkingCeiling() {
        assertThatThrownBy(() -> relay(new MutableClock(NOW), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryable-park-after");
    }

    @Test
    void publisherWritesEnvelopesWithTheRequestCorrelationId() {
        OutboxMandateEventPublisher publisher = new OutboxMandateEventPublisher(outbox, envelopes);
        MDC.put(CorrelationIdFilter.MDC_KEY, "ix-pub");

        publisher.publish(List.<MandateEvent>of(new MandateRevoked(UUID.randomUUID(), "CONS-1", 1L, NOW, "TPP-001", "x")));
        publisher.publish(List.of());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(r -> assertThat(r.getCorrelationId()).isEqualTo("ix-pub"));

        assertThat(rows.getValue().get(0).getTraceparent()).isNull();

        MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");
        MDC.put("spanId", "00f067aa0ba902b7");
        assertThat(OutboxMandateEventPublisher.currentTraceparent())
                .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        MDC.put("spanId", "not-hex");
        assertThat(OutboxMandateEventPublisher.currentTraceparent()).isNull();

        MDC.clear();
        assertThat(OutboxMandateEventPublisher.currentCorrelationId()).isNotBlank();
    }

    private OutboxRelay relay() {
        return relay(new MutableClock(NOW), Duration.ofHours(24));
    }

    private OutboxRelay relay(Clock clock, Duration retryableParkAfter) {
        return new OutboxRelay(outbox, kafka, transactions, clock, 100, Duration.ofMillis(200), Duration.ofDays(7),
                retryableParkAfter);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private OutboxEventJpaEntity row(String mandateId) {
        return envelopes.toOutboxRow(new MandateRevoked(UUID.randomUUID(), mandateId, 1L, NOW, "TPP-001", "x"), "ix-relay");
    }
}
