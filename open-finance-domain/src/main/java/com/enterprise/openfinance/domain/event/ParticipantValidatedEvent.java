package com.enterprise.openfinance.domain.event;

import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class ParticipantValidatedEvent implements DomainEvent {
    private final ParticipantId participantId;
    private final boolean isValid;
    private final String validationDetails;
    private final Instant occurredAt;
    private final String correlationId;
    private final String causationId;
    private final Long version;

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
            "isValid", isValid,
            "validationDetails", validationDetails,
            "validatedAt", occurredAt
        );
    }

    public static ParticipantValidatedEventBuilder builder() {
        return new ParticipantValidatedEventBuilder()
            .correlationId(UUID.randomUUID().toString())
            .causationId(UUID.randomUUID().toString())
            .version(1L);
    }
}