package com.enterprise.openfinance.recurringpayments.domain.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** The mandate was revoked; no further collections are allowed under it. */
public record MandateRevoked(
        UUID eventId,
        String mandateId,
        long aggregateVersion,
        Instant occurredAt,
        String tppId,
        String reason
) implements MandateEvent {

    public MandateRevoked {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(mandateId, "mandateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
