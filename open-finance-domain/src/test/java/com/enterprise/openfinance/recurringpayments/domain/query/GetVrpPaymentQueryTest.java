package com.enterprise.openfinance.recurringpayments.domain.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GetVrpPaymentQueryTest {

    @Test
    void shouldCreateAndNormalizePaymentQuery() {
        GetVrpPaymentQuery query = new GetVrpPaymentQuery(" PAY ", " TPP-001 ", " ix-1 ");

        assertThat(query.paymentId()).isEqualTo("PAY");
        assertThat(query.tppId()).isEqualTo("TPP-001");
        assertThat(query.interactionId()).isEqualTo("ix-1");
    }

    @Test
    void shouldRejectInvalidPaymentQuery() {
        assertInvalid("", "TPP-001", "ix-1", "paymentId");
        assertInvalid("PAY", "", "ix-1", "tppId");
        assertInvalid("PAY", "TPP-001", "", "interactionId");
    }

    private static void assertInvalid(String paymentId,
                                      String tppId,
                                      String interactionId,
                                      String expectedField) {
        assertThatThrownBy(() -> new GetVrpPaymentQuery(
                paymentId,
                tppId,
                interactionId
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
