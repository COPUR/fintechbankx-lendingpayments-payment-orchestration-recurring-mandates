package com.enterprise.openfinance.recurringpayments.infrastructure.event;

import com.enterprise.openfinance.recurringpayments.domain.event.MandateCreated;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateEvent;
import com.enterprise.openfinance.recurringpayments.domain.event.MandatePaymentAccepted;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateRevoked;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns mandate domain events into the public envelope of
 * api/asyncapi/svc-pay-recurring-mandates.yaml: topic
 * evt.pay.mandate.&lt;event&gt;.v1, eventType Payments.Mandate.&lt;Event&gt;.v1,
 * money as decimal strings, ids and facts only.
 */
public class MandateEventEnvelopeFactory {

    public static final String PRODUCER = "svc-pay-recurring-mandates";
    public static final String AGGREGATE_TYPE = "Mandate";
    public static final String TOPIC_PREFIX = "evt.pay.mandate.";

    private final ObjectMapper objectMapper;

    public MandateEventEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OutboxEventJpaEntity toOutboxRow(MandateEvent event, String correlationId) {
        return toOutboxRow(event, correlationId, null);
    }

    /**
     * @param traceparent W3C trace context of the request that raised the event, or null;
     *                    the relay forwards it as a record header so traces cross the broker
     */
    public OutboxEventJpaEntity toOutboxRow(MandateEvent event, String correlationId, String traceparent) {
        PublicEvent mapped = map(event);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId().toString());
        envelope.put("eventType", mapped.eventType());
        envelope.put("occurredAt", event.occurredAt().toString());
        envelope.put("aggregateId", event.mandateId());
        envelope.put("aggregateVersion", event.aggregateVersion());
        envelope.put("correlationId", correlationId);
        envelope.put("causationId", null);
        envelope.put("producer", PRODUCER);
        envelope.put("data", mapped.data());

        return new OutboxEventJpaEntity(event.eventId(), AGGREGATE_TYPE, event.mandateId(), event.aggregateVersion(),
                mapped.eventType(), mapped.topic(), toJson(envelope), correlationId, event.occurredAt(), traceparent);
    }

    static PublicEvent map(MandateEvent event) {
        return switch (event) {
            case MandateCreated e -> new PublicEvent("created", "Created", data(
                    "mandateId", e.mandateId(),
                    "tppId", e.tppId(),
                    "psuId", e.psuId(),
                    "maximumAmount", money(e.maxAmount(), e.currency()),
                    "periodType", "Month",
                    "expiresAt", e.expiresAt().toString(),
                    "debtorAccountLinked", e.debtorAccountLinked()));
            case MandateRevoked e -> new PublicEvent("revoked", "Revoked", data(
                    "mandateId", e.mandateId(),
                    "tppId", e.tppId(),
                    "reason", e.reason(),
                    "revokedAt", e.occurredAt().toString()));
            case MandatePaymentAccepted e -> new PublicEvent("payment-accepted", "PaymentAccepted", data(
                    "mandateId", e.mandateId(),
                    "paymentId", e.paymentId(),
                    "tppId", e.tppId(),
                    "amount", money(e.amount(), e.currency()),
                    "periodKey", e.periodKey(),
                    "periodTotal", money(e.periodTotal(), e.currency())));
        };
    }

    private static Map<String, Object> money(BigDecimal amount, String currency) {
        return data("amount", amount.toPlainString(), "currency", currency);
    }

    private static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private String toJson(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise mandate event envelope", e);
        }
    }

    record PublicEvent(String topicSuffix, String eventName, Map<String, Object> data) {
        String topic() {
            return TOPIC_PREFIX + topicSuffix + ".v1";
        }

        String eventType() {
            return "Payments.Mandate." + eventName + ".v1";
        }
    }
}
