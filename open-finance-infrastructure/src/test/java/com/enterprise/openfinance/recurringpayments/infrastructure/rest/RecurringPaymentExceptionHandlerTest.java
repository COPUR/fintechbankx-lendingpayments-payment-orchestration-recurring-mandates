package com.enterprise.openfinance.recurringpayments.infrastructure.rest;

import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.recurringpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto.VrpErrorResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class RecurringPaymentExceptionHandlerTest {

    private final RecurringPaymentExceptionHandler handler = new RecurringPaymentExceptionHandler();

    @Test
    void shouldMapForbiddenAndNotFoundAndConflict() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-FAPI-Interaction-ID", "ix-1");

        ResponseEntity<VrpErrorResponse> forbidden = handler.handleForbidden(new ForbiddenException("forbidden"), request);
        ResponseEntity<VrpErrorResponse> notFound = handler.handleNotFound(new ResourceNotFoundException("missing"), request);
        ResponseEntity<VrpErrorResponse> conflict = handler.handleConflict(new IdempotencyConflictException("conflict"), request);

        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void shouldMapBusinessRuleBadRequestAndUnexpected() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-FAPI-Interaction-ID", "ix-1");

        ResponseEntity<VrpErrorResponse> business = handler.handleBusinessRule(
                new BusinessRuleViolationException("Limit Exceeded"),
                request
        );
        ResponseEntity<VrpErrorResponse> badRequest = handler.handleBadRequest(new IllegalArgumentException("bad"), request);
        ResponseEntity<VrpErrorResponse> unexpected = handler.handleUnexpected(new RuntimeException("boom"), request);

        assertThat(business.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unexpected.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
