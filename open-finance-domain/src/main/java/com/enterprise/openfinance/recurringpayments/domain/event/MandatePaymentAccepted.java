package com.enterprise.openfinance.recurringpayments.domain.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A collection under the mandate was accepted within the monthly limit.
 * periodTotal is the accepted total for periodKey including this payment.
 */
public record MandatePaymentAccepted(
        UUID eventId,
        String mandateId,
        long aggregateVersion,
        Instant occurredAt,
        String paymentId,
        String tppId,
        BigDecimal amount,
        String currency,
        String periodKey,
        BigDecimal periodTotal
) implements MandateEvent {

    public MandatePaymentAccepted {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(mandateId, "mandateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(periodTotal, "periodTotal");
    }
}
