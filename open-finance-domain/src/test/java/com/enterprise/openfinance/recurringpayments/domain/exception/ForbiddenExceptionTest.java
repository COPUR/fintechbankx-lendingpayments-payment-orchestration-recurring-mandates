package com.enterprise.openfinance.recurringpayments.domain.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ForbiddenExceptionTest {

    @Test
    void shouldPreserveMessage() {
        assertThat(new ForbiddenException("forbidden")).hasMessage("forbidden");
    }
}
