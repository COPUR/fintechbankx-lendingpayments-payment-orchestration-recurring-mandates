package com.enterprise.openfinance.recurringpayments.infrastructure.rest;

import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.recurringpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto.VrpErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.enterprise.openfinance.recurringpayments.infrastructure.rest")
public class RecurringPaymentExceptionHandler {

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<VrpErrorResponse> handleForbidden(ForbiddenException exception,
                                                            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(VrpErrorResponse.of("FORBIDDEN", exception.getMessage(), interactionId(request)));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<VrpErrorResponse> handleNotFound(ResourceNotFoundException exception,
                                                           HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(VrpErrorResponse.of("NOT_FOUND", exception.getMessage(), interactionId(request)));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<VrpErrorResponse> handleConflict(IdempotencyConflictException exception,
                                                           HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(VrpErrorResponse.of("CONFLICT", exception.getMessage(), interactionId(request)));
    }

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ResponseEntity<VrpErrorResponse> handleBusinessRule(BusinessRuleViolationException exception,
                                                               HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(VrpErrorResponse.of("BUSINESS_RULE_VIOLATION", exception.getMessage(), interactionId(request)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<VrpErrorResponse> handleBadRequest(IllegalArgumentException exception,
                                                             HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(VrpErrorResponse.of("INVALID_REQUEST", exception.getMessage(), interactionId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<VrpErrorResponse> handleUnexpected(Exception exception,
                                                             HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(VrpErrorResponse.of("INTERNAL_ERROR", "Unexpected error occurred", interactionId(request)));
    }

    private static String interactionId(HttpServletRequest request) {
        return request.getHeader("X-FAPI-Interaction-ID");
    }
}
