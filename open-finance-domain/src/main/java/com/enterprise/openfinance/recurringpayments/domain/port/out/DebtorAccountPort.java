package com.enterprise.openfinance.recurringpayments.domain.port.out;

import com.enterprise.openfinance.recurringpayments.domain.model.DebtorAccount;

import java.util.Optional;

/**
 * Looks up the debtor account in the accounts context. Empty means the account
 * does not exist; an unreachable accounts service must raise, never return empty.
 */
public interface DebtorAccountPort {

    Optional<DebtorAccount> findDebtorAccount(String accountId);
}
