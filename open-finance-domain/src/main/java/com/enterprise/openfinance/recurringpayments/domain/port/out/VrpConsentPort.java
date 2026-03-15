package com.enterprise.openfinance.recurringpayments.domain.port.out;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;

import java.util.Optional;

public interface VrpConsentPort {
    VrpConsent save(VrpConsent consent);

    Optional<VrpConsent> findById(String consentId);
}
