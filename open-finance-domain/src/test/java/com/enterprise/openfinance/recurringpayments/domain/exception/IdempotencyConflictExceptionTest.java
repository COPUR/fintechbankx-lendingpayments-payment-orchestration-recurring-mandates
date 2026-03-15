package com.enterprise.openfinance.recurringpayments.domain.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyConflictExceptionTest {

    @Test
    void shouldPreserveMessage() {
        assertThat(new IdempotencyConflictException("Idempotency conflict")).hasMessage("Idempotency conflict");
    }
}
