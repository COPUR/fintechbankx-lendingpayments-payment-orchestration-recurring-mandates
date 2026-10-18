package com.enterprise.openfinance.recurringpayments.infrastructure.external;

import com.enterprise.openfinance.recurringpayments.domain.model.PsuConsent;
import com.enterprise.openfinance.recurringpayments.domain.port.out.PsuConsentPort;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Demo consents for local runs only (mandates.consent.adapter=in-memory);
 * never selected in a deployed environment.
 */
public class InMemoryPsuConsentAdapter implements PsuConsentPort {

    private static final Instant EXPIRY = Instant.parse("2099-12-31T23:59:59Z");

    private final Map<String, PsuConsent> consents = Map.of(
            "CONS-DEMO-VRP-1", new PsuConsent("CONS-DEMO-VRP-1", "TPP-001", "PSU-001",
                    Set.of(PsuConsent.VRP_SCOPE), Set.of("ACC-AED-ACTIVE"), EXPIRY, true),
            "CONS-DEMO-VRP-2", new PsuConsent("CONS-DEMO-VRP-2", "TPP-001", "PSU-002",
                    Set.of(PsuConsent.VRP_SCOPE), Set.of("ACC-AED-ACTIVE", "ACC-AED-BLOCKED"), EXPIRY, true),
            "CONS-DEMO-PENDING", new PsuConsent("CONS-DEMO-PENDING", "TPP-001", "PSU-001",
                    Set.of(PsuConsent.VRP_SCOPE), Set.of("ACC-AED-ACTIVE"), EXPIRY, false));

    @Override
    public Optional<PsuConsent> findConsent(String consentId) {
        return Optional.ofNullable(consents.get(consentId));
    }
}
