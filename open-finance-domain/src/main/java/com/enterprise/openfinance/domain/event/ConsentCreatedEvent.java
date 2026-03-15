package com.enterprise.openfinance.domain.event;

import com.enterprise.openfinance.domain.model.consent.ConsentId;
import com.enterprise.openfinance.domain.model.consent.ConsentPurpose;
import com.enterprise.openfinance.domain.model.consent.ConsentScope;
import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import com.enterprise.shared.domain.CustomerId;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Data
@Builder
public class ConsentCreatedEvent implements DomainEvent {
    private final ConsentId consentId;
    private final CustomerId customerId;
    private final ParticipantId participantId;
    private final Set<ConsentScope> scopes;
    private final ConsentPurpose purpose;
    private final Instant occurredAt;
    private final Instant expiresAt;
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
            "scopes", scopes,
            "purpose", purpose,
            "expiresAt", expiresAt
        );
    }

    public static ConsentCreatedEventBuilder builder() {
        return new ConsentCreatedEventBuilder()
            .correlationId(UUID.randomUUID().toString())
            .causationId(UUID.randomUUID().toString())
            .version(1L);
    }
}