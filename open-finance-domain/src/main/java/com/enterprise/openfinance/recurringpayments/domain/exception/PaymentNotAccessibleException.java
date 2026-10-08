package com.enterprise.openfinance.recurringpayments.domain.exception;

/**
 * The caller may not read this payment: unknown id or another TPP's payment.
 * Both get the same public message (and the same 403), so payment ids cannot
 * be probed. {@link #reason()} is for the service log only.
 */
public class PaymentNotAccessibleException extends ForbiddenException {

    public static final String MESSAGE = "Payment not found or not authorised";

    public enum Reason {
        NOT_FOUND,
        OTHER_TPP
    }

    private final Reason reason;

    public PaymentNotAccessibleException(Reason reason) {
        super(MESSAGE);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
