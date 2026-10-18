package com.enterprise.openfinance.recurringpayments.domain.model;

import com.enterprise.openfinance.recurringpayments.domain.event.MandateEvent;

import java.util.List;
import java.util.Objects;

/** New mandate state plus the events the change raised (empty when nothing changed). */
public record MandateChange(VrpConsent mandate, List<MandateEvent> events) {

    public MandateChange {
        Objects.requireNonNull(mandate, "mandate");
        events = List.copyOf(events);
    }

    public boolean changed() {
        return !events.isEmpty();
    }
}
