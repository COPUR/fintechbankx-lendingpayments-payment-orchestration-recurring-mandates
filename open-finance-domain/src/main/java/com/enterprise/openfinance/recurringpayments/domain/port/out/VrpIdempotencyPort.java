package com.enterprise.openfinance.recurringpayments.domain.port.out;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpIdempotencyRecord;

import java.time.Instant;
import java.util.Optional;

public interface VrpIdempotencyPort {
    Optional<VrpIdempotencyRecord> find(String idempotencyKey, String tppId, Instant now);

    void save(VrpIdempotencyRecord record);
}
