package com.enterprise.openfinance.recurringpayments.domain.model;

import java.math.BigDecimal;
import java.time.Instant;

public record VrpConsent(
        String consentId,
        String tppId,
        String psuId,
        BigDecimal maxAmount,
        String currency,
        VrpConsentStatus status,
        Instant expiresAt,
        Instant revokedAt
) {

    public VrpConsent {
        if (isBlank(consentId)) {
            throw new IllegalArgumentException("consentId is required");
        }
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
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }

        consentId = consentId.trim();
        tppId = tppId.trim();
        psuId = psuId.trim();
        currency = currency.trim();
    }

    public boolean belongsToTpp(String candidateTppId) {
        return tppId.equals(candidateTppId);
    }

    public boolean isActive(Instant now) {
        return status == VrpConsentStatus.AUTHORISED && expiresAt.isAfter(now);
    }

    public boolean isRevoked() {
        return status == VrpConsentStatus.REVOKED;
    }

    public VrpConsent revoke(Instant at) {
        return new VrpConsent(consentId, tppId, psuId, maxAmount, currency, VrpConsentStatus.REVOKED, expiresAt, at);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
