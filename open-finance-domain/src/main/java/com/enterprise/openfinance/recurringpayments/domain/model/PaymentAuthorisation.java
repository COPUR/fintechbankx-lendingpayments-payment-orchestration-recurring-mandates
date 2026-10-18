package com.enterprise.openfinance.recurringpayments.domain.model;

import com.enterprise.openfinance.recurringpayments.domain.event.MandateEvent;
import com.enterprise.openfinance.recurringpayments.domain.event.MandatePaymentAccepted;

import java.util.List;
import java.util.Objects;

/** Outcome of an accepted collection: the mandate at its new version, the payment and the event. */
public record PaymentAuthorisation(VrpConsent mandate, VrpPayment payment, MandatePaymentAccepted event) {

    public PaymentAuthorisation {
        Objects.requireNonNull(mandate, "mandate");
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(event, "event");
    }

    public List<MandateEvent> events() {
        return List.of(event);
    }
}
