package com.enterprise.openfinance.recurringpayments.infrastructure.event;

import com.enterprise.openfinance.recurringpayments.domain.event.MandateEvent;
import com.enterprise.openfinance.recurringpayments.domain.port.out.MandateEventPublisher;
import com.enterprise.openfinance.recurringpayments.infrastructure.observability.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Transactional outbox: writes each event's envelope in the caller's
 * transaction (MANDATORY), so the mandate change and its events commit or roll
 * back together. {@link OutboxRelay} ships them to Kafka afterwards.
 */
@Component
public class OutboxMandateEventPublisher implements MandateEventPublisher {

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("[0-9a-f]{16}");

    private final SpringDataOutboxRepository outbox;
    private final MandateEventEnvelopeFactory envelopes;

    public OutboxMandateEventPublisher(SpringDataOutboxRepository outbox, MandateEventEnvelopeFactory envelopes) {
        this.outbox = outbox;
        this.envelopes = envelopes;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(List<MandateEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        String correlationId = currentCorrelationId();
        String traceparent = currentTraceparent();
        outbox.saveAll(events.stream()
                .map(event -> envelopes.toOutboxRow(event, correlationId, traceparent))
                .toList());
    }

    /** W3C traceparent from the Micrometer Tracing MDC (traceId, spanId), or null outside a trace. */
    static String currentTraceparent() {
        String traceId = MDC.get("traceId");
        String spanId = MDC.get("spanId");
        if (traceId == null || spanId == null || !TRACE_ID.matcher(traceId).matches() || !SPAN_ID.matcher(spanId).matches()) {
            return null;
        }
        return "00-" + traceId + "-" + spanId + "-01";
    }

    static String currentCorrelationId() {
        String fromRequest = MDC.get(CorrelationIdFilter.MDC_KEY);
        return fromRequest != null ? fromRequest : UUID.randomUUID().toString();
    }
}
