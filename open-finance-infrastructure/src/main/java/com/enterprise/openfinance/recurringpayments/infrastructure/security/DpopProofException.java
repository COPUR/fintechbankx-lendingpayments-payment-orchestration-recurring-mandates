package com.enterprise.openfinance.recurringpayments.infrastructure.security;

/** A DPoP proof or token binding that fails RFC 9449 checks; answered with 401. */
public class DpopProofException extends RuntimeException {

    public DpopProofException(String message) {
        super(message);
    }

    public DpopProofException(String message, Throwable cause) {
        super(message, cause);
    }
}
