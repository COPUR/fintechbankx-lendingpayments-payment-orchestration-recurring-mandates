package com.enterprise.openfinance.recurringpayments.domain.command;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubmitVrpPaymentCommandTest {

    @Test
    void shouldCreateNormalizeAndHashSubmitCommand() {
        SubmitVrpPaymentCommand command = new SubmitVrpPaymentCommand(
                " TPP-001 ",
                " CONS-001 ",
                " IDEMP-001 ",
                new BigDecimal("100.00"),
                " AED ",
                " ix-1 "
        );

        assertThat(command.tppId()).isEqualTo("TPP-001");
        assertThat(command.consentId()).isEqualTo("CONS-001");
        assertThat(command.idempotencyKey()).isEqualTo("IDEMP-001");
        assertThat(command.currency()).isEqualTo("AED");
        assertThat(command.interactionId()).isEqualTo("ix-1");
        assertThat(command.requestHash()).isEqualTo("CONS-001|100.00|AED");
    }

    @Test
    void shouldRejectInvalidSubmitCommand() {
        assertInvalid("", "CONS-001", "IDEMP-001", new BigDecimal("100.00"), "AED", "ix-1", "tppId");
        assertInvalid("TPP-001", "", "IDEMP-001", new BigDecimal("100.00"), "AED", "ix-1", "consentId");
        assertInvalid("TPP-001", "CONS-001", "", new BigDecimal("100.00"), "AED", "ix-1", "idempotencyKey");
        assertInvalid("TPP-001", "CONS-001", "IDEMP-001", null, "AED", "ix-1", "amount");
        assertInvalid("TPP-001", "CONS-001", "IDEMP-001", new BigDecimal("0.00"), "AED", "ix-1", "amount");
        assertInvalid("TPP-001", "CONS-001", "IDEMP-001", new BigDecimal("-1.00"), "AED", "ix-1", "amount");
        assertInvalid("TPP-001", "CONS-001", "IDEMP-001", new BigDecimal("100.00"), "", "ix-1", "currency");
        assertInvalid("TPP-001", "CONS-001", "IDEMP-001", new BigDecimal("100.00"), "AED", "", "interactionId");
    }

    private static void assertInvalid(String tppId,
                                      String consentId,
                                      String idempotencyKey,
                                      BigDecimal amount,
                                      String currency,
                                      String interactionId,
                                      String expectedField) {
        assertThatThrownBy(() -> new SubmitVrpPaymentCommand(
                tppId,
                consentId,
                idempotencyKey,
                amount,
                currency,
                interactionId
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
