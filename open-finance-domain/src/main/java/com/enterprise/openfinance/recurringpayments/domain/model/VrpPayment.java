package com.enterprise.openfinance.recurringpayments.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

public record VrpPayment(
        String paymentId,
        String consentId,
        String tppId,
        String idempotencyKey,
        Money instructedAmount,
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
        if (instructedAmount == null || !instructedAmount.isPositive()) {
            throw new IllegalArgumentException("amount must be positive");
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
        periodKey = periodKey.trim();
    }

    /** Rehydration from stored primitives (persistence, tests). */
    public VrpPayment(String paymentId,
                      String consentId,
                      String tppId,
                      String idempotencyKey,
                      BigDecimal amount,
                      String currency,
                      String periodKey,
                      VrpPaymentStatus status,
                      Instant createdAt) {
        this(paymentId, consentId, tppId, idempotencyKey, amount == null ? null : Money.of(amount, currency),
                periodKey, status, createdAt);
    }

    public BigDecimal amount() {
        return instructedAmount.amount();
    }

    public String currency() {
        return instructedAmount.currencyCode();
    }

    public boolean isAccepted() {
        return status == VrpPaymentStatus.ACCEPTED;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
