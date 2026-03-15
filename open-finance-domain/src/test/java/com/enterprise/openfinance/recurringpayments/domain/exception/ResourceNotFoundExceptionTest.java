package com.enterprise.openfinance.recurringpayments.domain.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceNotFoundExceptionTest {

    @Test
    void shouldPreserveMessage() {
        assertThat(new ResourceNotFoundException("missing")).hasMessage("missing");
    }
}
