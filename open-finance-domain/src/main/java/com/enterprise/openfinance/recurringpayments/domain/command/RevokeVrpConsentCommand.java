package com.enterprise.openfinance.recurringpayments.domain.command;

public record RevokeVrpConsentCommand(
        String consentId,
        String tppId,
        String interactionId,
        String reason
) {

    public RevokeVrpConsentCommand {
        if (isBlank(consentId)) {
            throw new IllegalArgumentException("consentId is required");
        }
        if (isBlank(tppId)) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (isBlank(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }
        if (isBlank(reason)) {
            throw new IllegalArgumentException("reason is required");
        }

        consentId = consentId.trim();
        tppId = tppId.trim();
        interactionId = interactionId.trim();
        reason = reason.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
