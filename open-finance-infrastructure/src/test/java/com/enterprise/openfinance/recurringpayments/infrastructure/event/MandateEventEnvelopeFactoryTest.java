package com.enterprise.openfinance.recurringpayments.infrastructure.event;

import com.enterprise.openfinance.recurringpayments.domain.event.MandateCreated;
import com.enterprise.openfinance.recurringpayments.domain.event.MandatePaymentAccepted;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateRevoked;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MandateEventEnvelopeFactoryTest {

    private static final Instant AT = Instant.parse("2026-02-09T10:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("7a3f0c4e-2f7b-4f53-9f2c-0d2f0b6c1a11");
    private final ObjectMapper json = JsonMapper.builder().build();
    private final MandateEventEnvelopeFactory factory = new MandateEventEnvelopeFactory(json);

    @Test
    void createdEventBecomesTheStandardEnvelopeOnTheCreatedTopic() throws Exception {
        OutboxEventJpaEntity row = factory.toOutboxRow(new MandateCreated(EVENT_ID, "CONS-1", 0L, AT, "TPP-001",
                "PSU-001", new BigDecimal("5000.00"), "AED", Instant.parse("2099-01-01T00:00:00Z"), true), "ix-1");

        assertThat(row.getTopic()).isEqualTo("evt.pay.mandate.created.v1");
        assertThat(row.getEventType()).isEqualTo("Payments.Mandate.Created.v1");
        assertThat(row.getAggregateType()).isEqualTo("Mandate");
        assertThat(row.getAggregateId()).isEqualTo("CONS-1");
        assertThat(row.getEventId()).isEqualTo(EVENT_ID);
        assertThat(row.getCorrelationId()).isEqualTo("ix-1");
        assertThat(row.getOccurredAt()).isEqualTo(AT);
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getParkedAt()).isNull();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isNull();

        JsonNode envelope = json.readTree(row.getPayload());
        assertThat(envelope.fieldNames()).toIterable().containsExactly("eventId", "eventType", "occurredAt",
                "aggregateId", "aggregateVersion", "correlationId", "causationId", "producer", "data");
        assertThat(envelope.get("eventId").asText()).isEqualTo(EVENT_ID.toString());
        assertThat(envelope.get("occurredAt").asText()).isEqualTo("2026-02-09T10:00:00Z");
        assertThat(envelope.get("aggregateVersion").asLong()).isZero();
        assertThat(envelope.get("causationId").isNull()).isTrue();
        assertThat(envelope.get("producer").asText()).isEqualTo("svc-pay-recurring-mandates");
        assertThat(envelope.at("/data/maximumAmount/amount").asText()).isEqualTo("5000.00");
        assertThat(envelope.at("/data/maximumAmount/currency").asText()).isEqualTo("AED");
        assertThat(envelope.at("/data/periodType").asText()).isEqualTo("Month");
        assertThat(envelope.at("/data/debtorAccountLinked").asBoolean()).isTrue();
        assertThat(envelope.at("/data/expiresAt").asText()).isEqualTo("2099-01-01T00:00:00Z");
    }

    @Test
    void revokedAndPaymentAcceptedEventsMapToTheirTopicsAndPayloads() throws Exception {
        OutboxEventJpaEntity revoked = factory.toOutboxRow(
                new MandateRevoked(UUID.randomUUID(), "CONS-1", 3L, AT, "TPP-001", "Customer request"), "ix-2");
        assertThat(revoked.getTopic()).isEqualTo("evt.pay.mandate.revoked.v1");
        assertThat(revoked.getEventType()).isEqualTo("Payments.Mandate.Revoked.v1");
        assertThat(revoked.getAggregateVersion()).isEqualTo(3L);
        assertThat(json.readTree(revoked.getPayload()).at("/data/reason").asText()).isEqualTo("Customer request");
        assertThat(json.readTree(revoked.getPayload()).at("/data/revokedAt").asText()).isEqualTo("2026-02-09T10:00:00Z");

        OutboxEventJpaEntity accepted = factory.toOutboxRow(new MandatePaymentAccepted(UUID.randomUUID(), "CONS-1", 2L,
                AT, "PAY-1", "TPP-001", new BigDecimal("1250.00"), "AED", "2026-02", new BigDecimal("2000.00")), "ix-3");
        JsonNode data = json.readTree(accepted.getPayload()).get("data");
        assertThat(accepted.getTopic()).isEqualTo("evt.pay.mandate.payment-accepted.v1");
        assertThat(accepted.getEventType()).isEqualTo("Payments.Mandate.PaymentAccepted.v1");
        assertThat(data.get("paymentId").asText()).isEqualTo("PAY-1");
        assertThat(data.at("/amount/amount").asText()).isEqualTo("1250.00");
        assertThat(data.get("periodKey").asText()).isEqualTo("2026-02");
        assertThat(data.at("/periodTotal/amount").asText()).isEqualTo("2000.00");
    }

    @Test
    void serialisationFailureIsReported() throws Exception {
        ObjectMapper failing = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
                throw new com.fasterxml.jackson.core.JsonGenerationException("boom", (com.fasterxml.jackson.core.JsonGenerator) null);
            }
        };
        MandateEventEnvelopeFactory broken = new MandateEventEnvelopeFactory(failing);

        assertThatThrownBy(() -> broken.toOutboxRow(
                new MandateRevoked(UUID.randomUUID(), "CONS-1", 1L, AT, "TPP-001", "x"), "ix"))
                .isInstanceOf(IllegalStateException.class);
    }
}
