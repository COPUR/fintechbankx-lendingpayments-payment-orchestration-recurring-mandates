package com.enterprise.openfinance.recurringpayments.infrastructure.persistence;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpConsentPort;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryVrpConsentAdapter implements VrpConsentPort {

    private final Map<String, VrpConsent> data = new ConcurrentHashMap<>();

    @Override
    public VrpConsent save(VrpConsent consent) {
        data.put(consent.consentId(), consent);
        return consent;
    }

    @Override
    public Optional<VrpConsent> findById(String consentId) {
        return Optional.ofNullable(data.get(consentId));
    }
}
