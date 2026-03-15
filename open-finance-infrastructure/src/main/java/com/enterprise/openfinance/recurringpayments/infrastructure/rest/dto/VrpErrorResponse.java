package com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record VrpErrorResponse(
        String code,
        String message,
        String interactionId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp
) {

    public static VrpErrorResponse of(String code, String message, String interactionId) {
        return new VrpErrorResponse(code, message, interactionId, Instant.now());
    }
}
