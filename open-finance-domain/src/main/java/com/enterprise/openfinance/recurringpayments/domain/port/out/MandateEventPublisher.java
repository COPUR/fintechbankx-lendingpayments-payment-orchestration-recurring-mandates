package com.enterprise.openfinance.recurringpayments.domain.port.out;

import com.enterprise.openfinance.recurringpayments.domain.event.MandateEvent;

import java.util.List;

/**
 * Publishes mandate events. Implementations must write in the caller's
 * transaction (transactional outbox) so state and events commit together.
 */
public interface MandateEventPublisher {

    void publish(List<MandateEvent> events);
}
