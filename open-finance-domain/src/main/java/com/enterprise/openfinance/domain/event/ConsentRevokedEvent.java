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
public class ConsentRevokedEvent implements DomainEvent {
    private final ConsentId consentId;
    private final CustomerId customerId;
    private final ParticipantId participantId;
    private final String revocationReason;
    private final Instant occurredAt;
    private final String correlationId;
    private final String causationId;
    private final Long version;

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
            "revocationReason", revocationReason,
            "revokedAt", occurredAt
        );
    }

    public static ConsentRevokedEventBuilder builder() {
        return new ConsentRevokedEventBuilder()
            .correlationId(UUID.randomUUID().toString())
            .causationId(UUID.randomUUID().toString())
            .version(1L);
    }
}