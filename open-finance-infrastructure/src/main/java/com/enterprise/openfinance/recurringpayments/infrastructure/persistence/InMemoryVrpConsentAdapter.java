package com.enterprise.openfinance.recurringpayments.infrastructure.persistence;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpConsentPort;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-JVM adapter kept for unit tests and as a reference; not a Spring bean.
 * The service uses the PostgreSQL adapters so state survives restarts and is
 * shared across replicas.
 */
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
