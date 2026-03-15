package com.enterprise.openfinance.recurringpayments.domain.model;

import java.time.Duration;

public record VrpSettings(
        Duration idempotencyTtl,
        Duration cacheTtl
) {

    public VrpSettings {
        if (idempotencyTtl == null || idempotencyTtl.isNegative() || idempotencyTtl.isZero()) {
            throw new IllegalArgumentException("idempotencyTtl must be positive");
        }
        if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()) {
            throw new IllegalArgumentException("cacheTtl must be positive");
        }
    }
}
