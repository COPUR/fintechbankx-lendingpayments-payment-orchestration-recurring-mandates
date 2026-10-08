package com.enterprise.openfinance.recurringpayments.domain.model;

import java.time.Duration;
import java.time.ZoneId;

/**
 * @param limitPeriodZone zone whose calendar month bounds the cumulative VRP limit
 *                        (Asia/Dubai unless configured otherwise)
 */
public record VrpSettings(
        Duration idempotencyTtl,
        Duration cacheTtl,
        ZoneId limitPeriodZone
) {

    public VrpSettings {
        if (idempotencyTtl == null || idempotencyTtl.isNegative() || idempotencyTtl.isZero()) {
            throw new IllegalArgumentException("idempotencyTtl must be positive");
        }
        if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()) {
            throw new IllegalArgumentException("cacheTtl must be positive");
        }
        if (limitPeriodZone == null) {
            throw new IllegalArgumentException("limitPeriodZone is required");
        }
    }

    public VrpSettings(Duration idempotencyTtl, Duration cacheTtl) {
        this(idempotencyTtl, cacheTtl, VrpConsent.DEFAULT_LIMIT_PERIOD_ZONE);
    }
}
