package com.enterprise.openfinance.recurringpayments.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VrpConsentStatusTest {

    @Test
    void shouldExposeActiveFlag() {
        assertThat(VrpConsentStatus.AUTHORISED.isUsable()).isTrue();
        assertThat(VrpConsentStatus.REVOKED.isUsable()).isFalse();
        assertThat(VrpConsentStatus.EXPIRED.isUsable()).isFalse();
    }
}
