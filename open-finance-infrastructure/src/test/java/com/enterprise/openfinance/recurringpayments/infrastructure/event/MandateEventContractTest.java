package com.enterprise.openfinance.recurringpayments.infrastructure.event;

import com.enterprise.openfinance.recurringpayments.domain.event.MandateCreated;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateEvent;
import com.enterprise.openfinance.recurringpayments.domain.event.MandatePaymentAccepted;
import com.enterprise.openfinance.recurringpayments.domain.event.MandateRevoked;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the outbox envelopes and api/asyncapi/svc-pay-recurring-mandates.yaml
 * in step: the relay's topic is the spec's one aggregate channel, every event
 * type is a message of it, the eventType is the payload's and the headers'
 * const, and the data fields are exactly the schema's properties with every
 * required one present.
 */
class MandateEventContractTest {

    private static final Instant AT = Instant.parse("2026-02-09T10:00:00Z");
    private final ObjectMapper json = JsonMapper.builder().build();
    private final MandateEventEnvelopeFactory factory = new MandateEventEnvelopeFactory(json);

    @Test
    @SuppressWarnings("unchecked")
    void everyEnvelopeMatchesTheProviderAsyncApiSpec() throws Exception {
        Map<String, Object> spec;
        try (Reader reader = Files.newBufferedReader(Path.of("..", "api", "asyncapi", "svc-pay-recurring-mandates.yaml"))) {
            spec = new Yaml().load(reader);
        }
        Map<String, Map<String, Object>> channels = (Map<String, Map<String, Object>>) spec.get("channels");
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Map<String, Object>> schemas = (Map<String, Map<String, Object>>) components.get("schemas");
        Map<String, Map<String, Object>> messages = (Map<String, Map<String, Object>>) components.get("messages");

        List<MandateEvent> events = List.of(
                new MandateCreated(UUID.randomUUID(), "CONS-1", 0L, AT, "TPP-001", "PSU-001", new BigDecimal("5000.00"),
                        "AED", Instant.parse("2099-01-01T00:00:00Z"), false),
                new MandateRevoked(UUID.randomUUID(), "CONS-1", 1L, AT, "TPP-001", "Customer request"),
                new MandatePaymentAccepted(UUID.randomUUID(), "CONS-1", 2L, AT, "PAY-1", "TPP-001",
                        new BigDecimal("10.00"), "AED", "2026-02", new BigDecimal("10.00")));

        // One topic per aggregate (ADR-019): a single channel carries every mandate event type.
        // Provider spec: only the topic this service publishes. DLQs are consumer-owned
        // (ADR-019/024) and this service has no consumers.
        assertThat(channels).hasSize(1);
        Map<String, Object> channel = channels.values().iterator().next();
        assertThat(channel.get("address")).isEqualTo("evt.pay.mandate.v1");
        assertThat(((Map<String, Map<String, Object>>) channel.get("bindings")).get("kafka").get("topic"))
                .isEqualTo("evt.pay.mandate.v1");
        Map<String, Map<String, Object>> channelMessages = (Map<String, Map<String, Object>>) channel.get("messages");
        assertThat(channelMessages).hasSize(events.size());
        assertThat(messages).doesNotContainKey("DeadLetter");

        for (MandateEvent event : events) {
            OutboxEventJpaEntity row = factory.toOutboxRow(event, "ix-contract");
            assertThat(OutboxRelay.toRecord(row).topic()).isEqualTo(channel.get("address"));
            assertThat(channelMessages.get(event.getClass().getSimpleName()))
                    .containsEntry("$ref", "#/components/messages/" + event.getClass().getSimpleName());

            String messageName = event.getClass().getSimpleName();
            Map<String, Object> message = messages.get(messageName);
            assertThat(message.get("title")).isEqualTo(row.getEventType());
            assertThat(constOf(message.get("payload"), "eventType")).as(messageName).isEqualTo(row.getEventType());
            assertThat(constOf(message.get("headers"), "eventType")).as("%s eventType record header", messageName)
                    .isEqualTo(row.getEventType());
            assertThat((List<Map<String, Object>>) ((Map<String, Object>) message.get("headers")).get("allOf"))
                    .first().isEqualTo(Map.of("$ref", "#/components/schemas/EventHeaders"));
            Map<String, Object> schema = schemas.get(messageName + "Data");
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            List<String> required = (List<String>) schema.get("required");

            JsonNode data = json.readTree(row.getPayload()).get("data");
            List<String> fields = new ArrayList<>();
            data.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).as(messageName).containsExactlyInAnyOrderElementsOf(properties.keySet());
            assertThat(fields).as(messageName).containsAll(required);
        }
    }

    /** The const of a top-level property in the allOf parts of a payload or headers schema. */
    @SuppressWarnings("unchecked")
    private static Object constOf(Object schema, String property) {
        List<Map<String, Object>> parts = (List<Map<String, Object>>) ((Map<String, Object>) schema).get("allOf");
        assertThat(parts).as("allOf schema").isNotNull();
        for (Map<String, Object> part : parts) {
            Map<String, Map<String, Object>> props = (Map<String, Map<String, Object>>) part.get("properties");
            if (props != null && props.containsKey(property) && props.get(property).containsKey("const")) {
                return props.get(property).get("const");
            }
        }
        return null;
    }
}
