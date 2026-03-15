package com.enterprise.openfinance.recurringpayments.domain.command;

import java.math.BigDecimal;

public record SubmitVrpPaymentCommand(
        String tppId,
        String consentId,
        String idempotencyKey,
        BigDecimal amount,
        String currency,
        String interactionId
) {

    public SubmitVrpPaymentCommand {
        if (isBlank(tppId)) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (isBlank(consentId)) {
            throw new IllegalArgumentException("consentId is required");
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
        if (isBlank(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }

        tppId = tppId.trim();
        consentId = consentId.trim();
        idempotencyKey = idempotencyKey.trim();
        currency = currency.trim();
        interactionId = interactionId.trim();
    }

    public String requestHash() {
        return consentId + '|' + amount.toPlainString() + '|' + currency;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
