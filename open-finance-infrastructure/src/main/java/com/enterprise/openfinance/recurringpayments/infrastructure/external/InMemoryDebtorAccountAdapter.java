package com.enterprise.openfinance.recurringpayments.infrastructure.external;

import com.enterprise.openfinance.recurringpayments.domain.model.DebtorAccount;
import com.enterprise.openfinance.recurringpayments.domain.port.out.DebtorAccountPort;

import java.util.Map;
import java.util.Optional;

/**
 * Demo accounts for local runs and tests only (mandates.accounts.adapter=in-memory).
 * ACC-AED-ACTIVE is usable for AED mandates; ACC-AED-BLOCKED is not active.
 */
public class InMemoryDebtorAccountAdapter implements DebtorAccountPort {

    private static final Map<String, DebtorAccount> ACCOUNTS = Map.of(
            "ACC-AED-ACTIVE", new DebtorAccount("ACC-AED-ACTIVE", true, true, "AED"),
            "ACC-AED-BLOCKED", new DebtorAccount("ACC-AED-BLOCKED", false, true, "AED"));

    @Override
    public Optional<DebtorAccount> findDebtorAccount(String accountId) {
        return Optional.ofNullable(ACCOUNTS.get(accountId));
    }
}
