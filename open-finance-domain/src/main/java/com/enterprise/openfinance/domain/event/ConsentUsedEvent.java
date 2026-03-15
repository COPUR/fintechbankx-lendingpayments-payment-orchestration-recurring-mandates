package com.enterprise.openfinance.domain.event;

import com.enterprise.openfinance.domain.model.consent.ConsentId;
import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import com.enterprise.shared.domain.CustomerId;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class ConsentUsedEvent implements DomainEvent {
    private final ConsentId consentId;
    private final CustomerId customerId;
    private final ParticipantId participantId;
    private final AccessType accessType;
    private final String dataRequested;
    private final String ipAddress;
    private final String userAgent;
    private final Long processingTimeMs;
    private final Instant occurredAt;
    private final String correlationId;
    private final String causationId;
    private final Long version;

    public enum AccessType {
        ACCOUNT_INFORMATION,
        TRANSACTION_HISTORY,
        BALANCE_INQUIRY,
        PAYMENT_INITIATION
    }

    @Override
    public String getAggregateId() {
        return consentId.getValue();
    }

    @Override
    public String getAggregateType() {
        return "Consent";
    }

    @Override
    public Map<String, Object> getData() {
        return Map.of(
            "consentId", consentId.getValue(),
            "customerId", customerId.getValue(),
            "participantId", participantId.getValue(),
            "accessType", accessType,
            "dataRequested", dataRequested,
            "processingTimeMs", processingTimeMs
        );
    }

    public static ConsentUsedEventBuilder builder() {
        return new ConsentUsedEventBuilder()
            .correlationId(UUID.randomUUID().toString())
            .causationId(UUID.randomUUID().toString())
            .version(1L);
    }
}