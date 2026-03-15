package com.enterprise.openfinance.recurringpayments.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VrpPaymentStatusTest {

    @Test
    void shouldExposeApiValues() {
        assertThat(VrpPaymentStatus.ACCEPTED.apiValue()).isEqualTo("Accepted");
        assertThat(VrpPaymentStatus.REJECTED.apiValue()).isEqualTo("Rejected");
    }
}
