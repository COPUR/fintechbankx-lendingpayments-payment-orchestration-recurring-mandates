package com.enterprise.openfinance.recurringpayments.domain.port.out;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;

import java.util.Optional;

public interface VrpConsentPort {

    /**
     * Stores the mandate. A mandate at version 0 is inserted; a mandate at
     * version n replaces the stored version n - 1 and fails with
     * {@link com.enterprise.openfinance.recurringpayments.domain.exception.MandateVersionConflictException}
     * if another writer got there first.
     */
    VrpConsent save(VrpConsent consent);

    Optional<VrpConsent> findById(String consentId);
}
