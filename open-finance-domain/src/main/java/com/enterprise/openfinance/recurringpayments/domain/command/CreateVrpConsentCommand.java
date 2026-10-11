package com.enterprise.openfinance.recurringpayments.domain.command;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A TPP asks for a mandate under a consent the PSU authorised in the consent
 * service. psuId, expiresAt and debtorAccountId are optional: the consent
 * provides them, and when the request states them they must match it.
 */
public record CreateVrpConsentCommand(
        String tppId,
        String consentId,
        String psuId,
        BigDecimal maxAmount,
        String currency,
        Instant expiresAt,
        String interactionId,
        String debtorAccountId
) {

    public CreateVrpConsentCommand {
        if (isBlank(tppId)) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (isBlank(consentId)) {
            throw new IllegalArgumentException("consentId is required");
        }
        if (maxAmount == null || maxAmount.signum() <= 0) {
            throw new IllegalArgumentException("maxAmount must be positive");
        }
        if (isBlank(currency)) {
            throw new IllegalArgumentException("currency is required");
        }
        if (isBlank(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }

        tppId = tppId.trim();
        consentId = consentId.trim();
        psuId = isBlank(psuId) ? null : psuId.trim();
        currency = currency.trim();
        interactionId = interactionId.trim();
        debtorAccountId = isBlank(debtorAccountId) ? null : debtorAccountId.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
