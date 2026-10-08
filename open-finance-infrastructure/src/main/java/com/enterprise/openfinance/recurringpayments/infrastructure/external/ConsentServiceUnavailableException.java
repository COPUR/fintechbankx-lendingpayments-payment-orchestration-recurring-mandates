package com.enterprise.openfinance.recurringpayments.infrastructure.external;

/** The consent service could not answer; the request fails closed (HTTP 503). */
public class ConsentServiceUnavailableException extends RuntimeException {

    public ConsentServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
