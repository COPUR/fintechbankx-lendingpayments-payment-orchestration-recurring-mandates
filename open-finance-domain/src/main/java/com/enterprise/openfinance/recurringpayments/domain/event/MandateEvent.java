package com.enterprise.openfinance.recurringpayments.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A fact about one mandate (VRP consent), raised by the {@code VrpConsent}
 * aggregate and published under the evt.pay.mandate namespace. The mandate id
 * is the aggregate id and the Kafka key, so one mandate's events stay ordered.
 */
public sealed interface MandateEvent permits MandateCreated, MandateRevoked, MandatePaymentAccepted {

    UUID eventId();

    String mandateId();

    /** Mandate version after the change (0 for creation). */
    long aggregateVersion();

    Instant occurredAt();
}
