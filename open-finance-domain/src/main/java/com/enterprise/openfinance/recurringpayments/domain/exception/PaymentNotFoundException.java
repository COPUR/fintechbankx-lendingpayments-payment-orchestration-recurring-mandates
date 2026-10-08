package com.enterprise.openfinance.recurringpayments.domain.exception;

/** A payment path id that is unknown or another TPP's: one 404, "Payment not found". */
public class PaymentNotFoundException extends CallerScopedNotFoundException {

    public static final String MESSAGE = "Payment not found";

    public PaymentNotFoundException(Reason reason) {
        super(MESSAGE, reason);
    }
}
