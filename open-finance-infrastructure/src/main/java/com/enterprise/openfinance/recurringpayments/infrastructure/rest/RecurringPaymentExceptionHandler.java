package com.enterprise.openfinance.recurringpayments.infrastructure.rest;

import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ForbiddenException;
import com.enterprise.openfinance.recurringpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.recurringpayments.domain.exception.ResourceNotFoundException;
import com.enterprise.openfinance.recurringpayments.infrastructure.rest.dto.VrpErrorResponse;
import com.enterprise.openfinance.recurringpayments.domain.exception.MandateVersionConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.enterprise.openfinance.recurringpayments.infrastructure.rest")
public class RecurringPaymentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RecurringPaymentExceptionHandler.class);

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

    @ExceptionHandler(MandateVersionConflictException.class)
    public ResponseEntity<VrpErrorResponse> handleConcurrentUpdate(MandateVersionConflictException exception,
                                                                   HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(VrpErrorResponse.of("CONCURRENT_UPDATE", "Mandate was changed concurrently; retry",
                        interactionId(request)));
    }

    /** The accounts service could not be reached or failed: refuse, never assume the account is fine. */
    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<VrpErrorResponse> handleDependencyFailure(RestClientException exception,
                                                                    HttpServletRequest request) {
        log.warn("Accounts service call failed: {}", exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(VrpErrorResponse.of("DEPENDENCY_UNAVAILABLE", "Debtor account check is unavailable; retry later",
                        interactionId(request)));
    }

    @ExceptionHandler({ServletRequestBindingException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<VrpErrorResponse> handleMalformedRequest(Exception exception,
                                                                   HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(VrpErrorResponse.of("INVALID_REQUEST", "Malformed request: missing or invalid header, parameter or body",
                        interactionId(request)));
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
        // Spring MVC client errors (405, 415, 404 for unknown paths, ...) keep their status.
        if (exception instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            int status = errorResponse.getStatusCode().value();
            String code = status == HttpStatus.NOT_FOUND.value() ? "NOT_FOUND" : "INVALID_REQUEST";
            return ResponseEntity.status(status)
                    .body(VrpErrorResponse.of(code, errorResponse.getBody().getDetail(), interactionId(request)));
        }
        log.error("Unexpected error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(VrpErrorResponse.of("INTERNAL_ERROR", "Unexpected error occurred", interactionId(request)));
    }

    private static String interactionId(HttpServletRequest request) {
        return request.getHeader("X-FAPI-Interaction-ID");
    }
}
