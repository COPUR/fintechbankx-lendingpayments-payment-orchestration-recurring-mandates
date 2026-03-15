package com.enterprise.openfinance.recurringpayments.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

public record VrpPayment(
        String paymentId,
        String consentId,
        String tppId,
        String idempotencyKey,
        BigDecimal amount,
        String currency,
        String periodKey,
        VrpPaymentStatus status,
        Instant createdAt
) {

    public VrpPayment {
        if (isBlank(paymentId)) {
            throw new IllegalArgumentException("paymentId is required");
        }
        if (isBlank(consentId)) {
            throw new IllegalArgumentException("consentId is required");
        }
        if (isBlank(tppId)) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (isBlank(idempotencyKey)) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (isBlank(currency)) {
            throw new IllegalArgumentException("currency is required");
        }
        if (isBlank(periodKey)) {
            throw new IllegalArgumentException("periodKey is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }

        paymentId = paymentId.trim();
        consentId = consentId.trim();
        tppId = tppId.trim();
        idempotencyKey = idempotencyKey.trim();
        currency = currency.trim();
        periodKey = periodKey.trim();
    }

    public boolean isAccepted() {
        return status == VrpPaymentStatus.ACCEPTED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
