package com.enterprise.openfinance.recurringpayments.domain.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetVrpConsentQueryTest {

    @Test
    void shouldCreateAndNormalizeConsentQuery() {
        GetVrpConsentQuery query = new GetVrpConsentQuery(" CONS ", " TPP-001 ", " ix-1 ");

        assertThat(query.consentId()).isEqualTo("CONS");
        assertThat(query.tppId()).isEqualTo("TPP-001");
        assertThat(query.interactionId()).isEqualTo("ix-1");
    }

    @Test
    void shouldRejectInvalidConsentQuery() {
        assertInvalid("", "TPP-001", "ix-1", "consentId");
        assertInvalid("CONS", "", "ix-1", "tppId");
        assertInvalid("CONS", "TPP-001", "", "interactionId");
    }

    private static void assertInvalid(String consentId,
                                      String tppId,
                                      String interactionId,
                                      String expectedField) {
        assertThatThrownBy(() -> new GetVrpConsentQuery(
                consentId,
                tppId,
                interactionId
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
