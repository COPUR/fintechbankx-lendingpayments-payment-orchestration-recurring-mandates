package com.enterprise.openfinance.recurringpayments.domain.model;

import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;

/**
 * What this context needs to know about the PSU's debtor account. The
 * accounts context owns the account; this is a read model fetched through
 * {@code DebtorAccountPort}, never stored.
 */
public record DebtorAccount(String accountId, boolean active, boolean debitAllowed, String currency) {

    /**
     * The one answer for every debtor account a mandate cannot use: unknown,
     * outside the PSU's consent, inactive, not debitable or in another
     * currency. A single message (and a single 400) means a caller cannot use
     * the API to learn whether an account exists or whose it is.
     */
    public static final String NOT_USABLE = "DebtorAccount cannot be used for this mandate";

    public static BusinessRuleViolationException notUsable() {
        return new BusinessRuleViolationException(NOT_USABLE);
    }

    public DebtorAccount {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
    }

    public void ensureDebitableIn(String mandateCurrency) {
        if (!active || !debitAllowed || currency == null || !currency.equalsIgnoreCase(mandateCurrency)) {
            throw notUsable();
        }
    }
}
