package com.enterprise.openfinance.recurringpayments.domain.exception;

/**
 * A TPP-facing path id the caller cannot see (ADR-025 item 5): an id that does
 * not exist and an id that belongs to another TPP get the same 404 with one
 * fixed message per resource type, so a TPP cannot probe other TPPs' ids.
 * {@link #reason()} is for the service log only.
 */
public abstract class CallerScopedNotFoundException extends ResourceNotFoundException {

    public enum Reason {
        NOT_FOUND,
        OTHER_TPP
    }

    private final Reason reason;

    protected CallerScopedNotFoundException(String message, Reason reason) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
