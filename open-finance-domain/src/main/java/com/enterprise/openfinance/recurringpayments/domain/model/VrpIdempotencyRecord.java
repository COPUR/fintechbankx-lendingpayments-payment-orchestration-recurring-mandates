package com.enterprise.openfinance.recurringpayments.domain.model;

import java.time.Instant;

public record VrpIdempotencyRecord(
        String idempotencyKey,
        String tppId,
        String requestHash,
        String paymentId,
        VrpPaymentStatus status,
        Instant expiresAt
) {

    public VrpIdempotencyRecord {
        if (isBlank(idempotencyKey)) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (isBlank(tppId)) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (isBlank(requestHash)) {
            throw new IllegalArgumentException("requestHash is required");
        }
        if (isBlank(paymentId)) {
            throw new IllegalArgumentException("paymentId is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }

        idempotencyKey = idempotencyKey.trim();
        tppId = tppId.trim();
        requestHash = requestHash.trim();
        paymentId = paymentId.trim();
    }

    public boolean isActive(Instant now) {
        return expiresAt.isAfter(now);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
