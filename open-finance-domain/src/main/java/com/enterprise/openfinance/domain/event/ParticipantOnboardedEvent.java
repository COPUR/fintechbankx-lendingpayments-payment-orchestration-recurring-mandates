package com.enterprise.openfinance.domain.event;

import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class ParticipantOnboardedEvent implements DomainEvent {
    private final ParticipantId participantId;
    private final String legalName;
    private final ParticipantRole role;
    private final Instant occurredAt;
    private final String correlationId;
    private final String causationId;
    private final Long version;

    public enum ParticipantRole {
        ASPSP, // Account Servicing Payment Service Provider
        PISP,  // Payment Initiation Service Provider
        AISP,  // Account Information Service Provider
        CBPII  // Card Based Payment Instrument Issuer
    }

    @Override
    public String getAggregateId() {
        return participantId.getValue();
    }

    @Override
    public String getAggregateType() {
        return "Participant";
    }

    @Override
    public Map<String, Object> getData() {
        return Map.of(
            "participantId", participantId.getValue(),
            "legalName", legalName,
            "role", role,
            "onboardedAt", occurredAt
        );
    }

    public static ParticipantOnboardedEventBuilder builder() {
        return new ParticipantOnboardedEventBuilder()
            .correlationId(UUID.randomUUID().toString())
            .causationId(UUID.randomUUID().toString())
            .version(1L);
    }
}