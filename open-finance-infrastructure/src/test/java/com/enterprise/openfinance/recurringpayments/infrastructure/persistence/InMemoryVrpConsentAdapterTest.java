package com.enterprise.openfinance.recurringpayments.infrastructure.persistence;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsentStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class InMemoryVrpConsentAdapterTest {

    @Test
    void shouldSaveAndFindConsent() {
        InMemoryVrpConsentAdapter adapter = new InMemoryVrpConsentAdapter();
        VrpConsent consent = new VrpConsent(
                "CONS-VRP-UNIT",
                "TPP-001",
                "PSU-001",
                new BigDecimal("5000.00"),
                "AED",
                VrpConsentStatus.AUTHORISED,
                Instant.parse("2099-01-01T00:00:00Z"),
                null
        );

        adapter.save(consent);

        assertThat(adapter.findById("CONS-VRP-UNIT")).isPresent();
        assertThat(adapter.findById("CONS-UNKNOWN")).isEmpty();
    }
}
