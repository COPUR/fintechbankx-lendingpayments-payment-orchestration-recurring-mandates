package com.enterprise.openfinance.recurringpayments.domain.command;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateVrpConsentCommandTest {

    @Test
    void shouldCreateAndNormalizeCommand() {
        CreateVrpConsentCommand command = new CreateVrpConsentCommand(
                " TPP-001 ",
                " CONS-AUTH-1 ",
                " PSU-001 ",
                new BigDecimal("5000.00"),
                " AED ",
                Instant.parse("2099-01-01T00:00:00Z"),
                " ix-1 ",
                " "
        );

        assertThat(command.tppId()).isEqualTo("TPP-001");
        assertThat(command.psuId()).isEqualTo("PSU-001");
        assertThat(command.currency()).isEqualTo("AED");
        assertThat(command.interactionId()).isEqualTo("ix-1");
        assertThat(command.consentId()).isEqualTo("CONS-AUTH-1");
        assertThat(command.debtorAccountId()).isNull();
    }

    @Test
    void psuDebtorAccountAndExpiryAreOptionalBecauseTheConsentProvidesThem() {
        CreateVrpConsentCommand command = new CreateVrpConsentCommand("TPP-001", "CONS-AUTH-1", null,
                new BigDecimal("5000.00"), "AED", null, "ix-1", null);
        assertThat(command.psuId()).isNull();
        assertThat(command.expiresAt()).isNull();
        assertThatThrownBy(() -> new CreateVrpConsentCommand("TPP-001", " ", null, new BigDecimal("5000.00"), "AED",
                null, "ix-1", null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("consentId");
    }

    @Test
    void shouldRejectInvalidCreateCommand() {
        assertInvalid("", "PSU-001", new BigDecimal("5000.00"), "AED", Instant.parse("2099-01-01T00:00:00Z"), "ix-1", "tppId");
        assertInvalid("TPP-001", "PSU-001", null, "AED", Instant.parse("2099-01-01T00:00:00Z"), "ix-1", "maxAmount");
        assertInvalid("TPP-001", "PSU-001", new BigDecimal("0.00"), "AED", Instant.parse("2099-01-01T00:00:00Z"), "ix-1", "maxAmount");
        assertInvalid("TPP-001", "PSU-001", new BigDecimal("-1.00"), "AED", Instant.parse("2099-01-01T00:00:00Z"), "ix-1", "maxAmount");
        assertInvalid("TPP-001", "PSU-001", new BigDecimal("5000.00"), "", Instant.parse("2099-01-01T00:00:00Z"), "ix-1", "currency");
        assertInvalid("TPP-001", "PSU-001", new BigDecimal("5000.00"), "AED", Instant.parse("2099-01-01T00:00:00Z"), "", "interactionId");
    }

    private static void assertInvalid(String tppId,
                                      String psuId,
                                      BigDecimal maxAmount,
                                      String currency,
                                      Instant expiresAt,
                                      String interactionId,
                                      String expectedField) {
        assertThatThrownBy(() -> new CreateVrpConsentCommand(
                tppId,
                "CONS-AUTH-1",
                psuId,
                maxAmount,
                currency,
                expiresAt,
                interactionId,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
