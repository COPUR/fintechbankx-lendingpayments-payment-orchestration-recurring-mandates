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
                " PSU-001 ",
                new BigDecimal("5000.00"),
                " AED ",
                Instant.parse("2099-01-01T00:00:00Z"),
                " ix-1 "
        );

        assertThat(command.tppId()).isEqualTo("TPP-001");
        assertThat(command.psuId()).isEqualTo("PSU-001");
        assertThat(command.currency()).isEqualTo("AED");
        assertThat(command.interactionId()).isEqualTo("ix-1");
    }

    @Test
    void shouldRejectInvalidCreateCommand() {
        assertInvalid("", "PSU-001", new BigDecimal("5000.00"), "AED", Instant.parse("2099-01-01T00:00:00Z"), "ix-1", "tppId");
        assertInvalid("TPP-001", "", new BigDecimal("5000.00"), "AED", Instant.parse("2099-01-01T00:00:00Z"), "ix-1", "psuId");
        assertInvalid("TPP-001", "PSU-001", null, "AED", Instant.parse("2099-01-01T00:00:00Z"), "ix-1", "maxAmount");
        assertInvalid("TPP-001", "PSU-001", new BigDecimal("0.00"), "AED", Instant.parse("2099-01-01T00:00:00Z"), "ix-1", "maxAmount");
        assertInvalid("TPP-001", "PSU-001", new BigDecimal("-1.00"), "AED", Instant.parse("2099-01-01T00:00:00Z"), "ix-1", "maxAmount");
        assertInvalid("TPP-001", "PSU-001", new BigDecimal("5000.00"), "", Instant.parse("2099-01-01T00:00:00Z"), "ix-1", "currency");
        assertInvalid("TPP-001", "PSU-001", new BigDecimal("5000.00"), "AED", null, "ix-1", "expiresAt");
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
                psuId,
                maxAmount,
                currency,
                expiresAt,
                interactionId
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
