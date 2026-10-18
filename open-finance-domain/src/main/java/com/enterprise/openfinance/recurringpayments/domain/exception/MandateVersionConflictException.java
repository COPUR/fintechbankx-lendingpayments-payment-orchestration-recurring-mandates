package com.enterprise.openfinance.recurringpayments.domain.exception;

/** Another request changed the mandate first; the caller may retry. */
public class MandateVersionConflictException extends RuntimeException {

    public MandateVersionConflictException(String message) {
        super(message);
    }
}
