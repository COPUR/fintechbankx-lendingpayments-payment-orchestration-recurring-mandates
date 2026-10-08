package com.enterprise.openfinance.recurringpayments.domain.port.out;

import com.enterprise.openfinance.recurringpayments.domain.model.PsuConsent;

import java.util.Optional;

/**
 * Reads a PSU-authorised consent from the consent service, which owns
 * consents. Empty when the consent does not exist. Implementations fail
 * closed when the consent service cannot answer.
 */
public interface PsuConsentPort {

    Optional<PsuConsent> findConsent(String consentId);
}
