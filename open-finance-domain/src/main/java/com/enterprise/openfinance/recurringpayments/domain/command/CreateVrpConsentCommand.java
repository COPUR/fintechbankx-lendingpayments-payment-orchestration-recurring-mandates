package com.enterprise.openfinance.recurringpayments.domain.command;

import java.math.BigDecimal;
import java.time.Instant;

public record CreateVrpConsentCommand(
        String tppId,
        String psuId,
        BigDecimal maxAmount,
        String currency,
        Instant expiresAt,
        String interactionId,
        String debtorAccountId
) {

    /** A mandate request without a debtor account to verify. */
    public CreateVrpConsentCommand(String tppId, String psuId, BigDecimal maxAmount, String currency,
                                   Instant expiresAt, String interactionId) {
        this(tppId, psuId, maxAmount, currency, expiresAt, interactionId, null);
    }

    public CreateVrpConsentCommand {
        if (isBlank(tppId)) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (isBlank(psuId)) {
            throw new IllegalArgumentException("psuId is required");
        }
        if (maxAmount == null || maxAmount.signum() <= 0) {
            throw new IllegalArgumentException("maxAmount must be positive");
        }
        if (isBlank(currency)) {
            throw new IllegalArgumentException("currency is required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }
        if (isBlank(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }

        tppId = tppId.trim();
        psuId = psuId.trim();
        currency = currency.trim();
        interactionId = interactionId.trim();
        debtorAccountId = isBlank(debtorAccountId) ? null : debtorAccountId.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
