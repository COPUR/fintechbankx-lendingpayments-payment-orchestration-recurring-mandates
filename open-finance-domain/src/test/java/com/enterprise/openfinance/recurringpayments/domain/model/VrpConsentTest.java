package com.enterprise.openfinance.recurringpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VrpConsentTest {

    @Test
    void shouldCreateAuthorisedConsentAndAllowRevocation() {
        VrpConsent consent = new VrpConsent(
                "CONS-VRP-001",
                "TPP-001",
                "PSU-001",
                new BigDecimal("5000.00"),
                "AED",
                VrpConsentStatus.AUTHORISED,
                Instant.parse("2099-01-01T00:00:00Z"),
                null
        );

        assertThat(consent.belongsToTpp("TPP-001")).isTrue();
        assertThat(consent.belongsToTpp("TPP-002")).isFalse();
        assertThat(consent.isActive(Instant.parse("2026-02-09T00:00:00Z"))).isTrue();
        assertThat(consent.isActive(Instant.parse("2100-01-01T00:00:00Z"))).isFalse();
        assertThat(consent.isRevoked()).isFalse();

        VrpConsent revoked = consent.revoke(Instant.parse("2026-02-09T10:00:00Z"));

        assertThat(revoked.status()).isEqualTo(VrpConsentStatus.REVOKED);
        assertThat(revoked.revokedAt()).isEqualTo(Instant.parse("2026-02-09T10:00:00Z"));
        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.isActive(Instant.parse("2026-02-09T00:00:00Z"))).isFalse();
    }

    @Test
    void shouldNormalizeFieldsOnCreate() {
        VrpConsent consent = new VrpConsent(
                " CONS-VRP-001 ",
                " TPP-001 ",
                " PSU-001 ",
                new BigDecimal("5000.00"),
                " AED ",
                VrpConsentStatus.AUTHORISED,
                Instant.parse("2099-01-01T00:00:00Z"),
                null
        );

        assertThat(consent.consentId()).isEqualTo("CONS-VRP-001");
        assertThat(consent.tppId()).isEqualTo("TPP-001");
        assertThat(consent.psuId()).isEqualTo("PSU-001");
        assertThat(consent.currency()).isEqualTo("AED");
    }

    @Test
    void shouldRejectInvalidConsentData() {
        assertInvalid("", "TPP-001", "PSU-001", new BigDecimal("5000.00"), "AED", VrpConsentStatus.AUTHORISED, Instant.parse("2099-01-01T00:00:00Z"), "consentId");
        assertInvalid("CONS", "", "PSU-001", new BigDecimal("5000.00"), "AED", VrpConsentStatus.AUTHORISED, Instant.parse("2099-01-01T00:00:00Z"), "tppId");
        assertInvalid("CONS", "TPP-001", "", new BigDecimal("5000.00"), "AED", VrpConsentStatus.AUTHORISED, Instant.parse("2099-01-01T00:00:00Z"), "psuId");
        assertInvalid("CONS", "TPP-001", "PSU-001", null, "AED", VrpConsentStatus.AUTHORISED, Instant.parse("2099-01-01T00:00:00Z"), "maxAmount");
        assertInvalid("CONS", "TPP-001", "PSU-001", new BigDecimal("0.00"), "AED", VrpConsentStatus.AUTHORISED, Instant.parse("2099-01-01T00:00:00Z"), "maxAmount");
        assertInvalid("CONS", "TPP-001", "PSU-001", new BigDecimal("5000.00"), "", VrpConsentStatus.AUTHORISED, Instant.parse("2099-01-01T00:00:00Z"), "currency");
        assertInvalid("CONS", "TPP-001", "PSU-001", new BigDecimal("5000.00"), "AED", null, Instant.parse("2099-01-01T00:00:00Z"), "status");
        assertInvalid("CONS", "TPP-001", "PSU-001", new BigDecimal("5000.00"), "AED", VrpConsentStatus.AUTHORISED, null, "expiresAt");
    }

    private static void assertInvalid(String consentId,
                                      String tppId,
                                      String psuId,
                                      BigDecimal maxAmount,
                                      String currency,
                                      VrpConsentStatus status,
                                      Instant expiresAt,
                                      String expectedField) {
        assertThatThrownBy(() -> new VrpConsent(
                consentId,
                tppId,
                psuId,
                maxAmount,
                currency,
                status,
                expiresAt,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
