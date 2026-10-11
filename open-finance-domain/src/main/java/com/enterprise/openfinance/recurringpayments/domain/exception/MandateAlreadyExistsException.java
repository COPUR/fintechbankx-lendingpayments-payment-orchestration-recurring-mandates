package com.enterprise.openfinance.recurringpayments.domain.exception;

/** The consent already carries a mandate; a consent authorises at most one (HTTP 409). */
public class MandateAlreadyExistsException extends RuntimeException {

    public MandateAlreadyExistsException(String message) {
        super(message);
    }
}
