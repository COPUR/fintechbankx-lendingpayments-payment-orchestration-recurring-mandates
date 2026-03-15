package com.enterprise.openfinance.recurringpayments.domain.port.out;

import java.util.function.Supplier;

public interface VrpLockPort {
    <T> T withConsentLock(String consentId, Supplier<T> operation);
}
