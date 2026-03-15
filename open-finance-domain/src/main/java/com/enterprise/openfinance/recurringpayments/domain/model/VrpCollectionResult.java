package com.enterprise.openfinance.recurringpayments.domain.model;

import java.time.Instant;

public record VrpCollectionResult(
        String paymentId,
        String consentId,
        VrpPaymentStatus status,
        String interactionId,
        Instant createdAt,
        boolean idempotencyReplay
) {

    public VrpCollectionResult {
        if (isBlank(paymentId)) {
            throw new IllegalArgumentException("paymentId is required");
        }
        if (isBlank(consentId)) {
            throw new IllegalArgumentException("consentId is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (isBlank(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }

        paymentId = paymentId.trim();
        consentId = consentId.trim();
        interactionId = interactionId.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
