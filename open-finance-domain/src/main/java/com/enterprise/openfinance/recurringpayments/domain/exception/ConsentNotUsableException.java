package com.enterprise.openfinance.recurringpayments.domain.exception;

/**
 * The caller may not use this consent. Every case has the same public message
 * (and the same 403), so a TPP cannot learn whether a consent id exists, whose
 * it is, or what state it is in. {@link #reason()} is for the service log only.
 */
public class ConsentNotUsableException extends ForbiddenException {

    public static final String MESSAGE = "Consent not found or not authorised";

    public enum Reason {
        /** The consent service does not know the id. */
        NOT_FOUND,
        /** Not authorised by the PSU (pending, rejected, revoked). */
        NOT_AUTHORISED,
        EXPIRED,
        /** Issued to another TPP. */
        OTHER_TPP,
        /** Does not grant INITIATEVRP. */
        MISSING_SCOPE
    }

    private final Reason reason;

    public ConsentNotUsableException(Reason reason) {
        super(MESSAGE);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
