package com.enterprise.openfinance.recurringpayments.domain.model;

import java.time.Instant;

/**
 * What a PSU-authorised consent allows a mandate to be: its id (the consent
 * id, so one consent has at most one mandate), the PSU, the debtor account
 * and the latest expiry.
 */
public record MandateTerms(String mandateId, String tppId, String psuId, String debtorAccountId, Instant expiresAt) {
}
