package com.enterprise.openfinance.recurringpayments.domain.query;

public record GetVrpPaymentQuery(
        String paymentId,
        String tppId,
        String interactionId
) {

    public GetVrpPaymentQuery {
        if (isBlank(paymentId)) {
            throw new IllegalArgumentException("paymentId is required");
        }
        if (isBlank(tppId)) {
            throw new IllegalArgumentException("tppId is required");
        }
        if (isBlank(interactionId)) {
            throw new IllegalArgumentException("interactionId is required");
        }

        paymentId = paymentId.trim();
        tppId = tppId.trim();
        interactionId = interactionId.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
