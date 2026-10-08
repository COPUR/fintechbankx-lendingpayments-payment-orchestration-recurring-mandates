package com.enterprise.openfinance.recurringpayments.domain.model;

import com.enterprise.openfinance.recurringpayments.domain.exception.BusinessRuleViolationException;

/**
 * What this context needs to know about the PSU's debtor account. The
 * accounts context owns the account; this is a read model fetched through
 * {@code DebtorAccountPort}, never stored.
 */
public record DebtorAccount(String accountId, boolean active, boolean debitAllowed, String currency) {

    public DebtorAccount {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
    }

    public void ensureDebitableIn(String mandateCurrency) {
        if (!active) {
            throw new BusinessRuleViolationException("Debtor account is not active");
        }
        if (!debitAllowed) {
            throw new BusinessRuleViolationException("Debtor account does not allow debits");
        }
        if (currency == null || !currency.equalsIgnoreCase(mandateCurrency)) {
            throw new BusinessRuleViolationException("Debtor account currency mismatch");
        }
    }
}
