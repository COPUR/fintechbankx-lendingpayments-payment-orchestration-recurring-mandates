package com.enterprise.openfinance.recurringpayments.domain.exception;

/** A mandate path id that is unknown or another TPP's: one 404, "Consent not found". */
public class ConsentNotFoundException extends CallerScopedNotFoundException {

    public static final String MESSAGE = "Consent not found";

    public ConsentNotFoundException(Reason reason) {
        super(MESSAGE, reason);
    }
}
