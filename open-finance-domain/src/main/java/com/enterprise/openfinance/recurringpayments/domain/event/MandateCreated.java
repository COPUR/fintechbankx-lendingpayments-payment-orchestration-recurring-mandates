package com.enterprise.openfinance.recurringpayments.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A TPP's variable recurring payment mandate was authorised. */
public record MandateCreated(
        UUID eventId,
        String mandateId,
        long aggregateVersion,
        Instant occurredAt,
        String tppId,
        String psuId,
        BigDecimal maxAmount,
        String currency,
        Instant expiresAt,
        boolean debtorAccountLinked
) implements MandateEvent {

    public MandateCreated {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(mandateId, "mandateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(maxAmount, "maxAmount");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
