package com.enterprise.openfinance.recurringpayments.domain.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RevokeVrpConsentCommandTest {

    @Test
    void shouldCreateAndNormalizeRevokeCommand() {
        RevokeVrpConsentCommand command = new RevokeVrpConsentCommand(
                " CONS-001 ",
                " TPP-001 ",
                " ix-1 ",
                " user request "
        );

        assertThat(command.consentId()).isEqualTo("CONS-001");
        assertThat(command.tppId()).isEqualTo("TPP-001");
        assertThat(command.interactionId()).isEqualTo("ix-1");
        assertThat(command.reason()).isEqualTo("user request");
    }

    @Test
    void shouldRejectInvalidRevokeCommand() {
        assertInvalid("", "TPP-001", "ix-1", "reason", "consentId");
        assertInvalid("CONS", "", "ix-1", "reason", "tppId");
        assertInvalid("CONS", "TPP-001", "", "reason", "interactionId");
        assertInvalid("CONS", "TPP-001", "ix-1", "", "reason");
    }

    private static void assertInvalid(String consentId,
                                      String tppId,
                                      String interactionId,
                                      String reason,
                                      String expectedField) {
        assertThatThrownBy(() -> new RevokeVrpConsentCommand(
                consentId,
                tppId,
                interactionId,
                reason
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
