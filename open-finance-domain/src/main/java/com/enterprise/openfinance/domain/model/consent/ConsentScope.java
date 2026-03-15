package com.enterprise.openfinance.domain.model.consent;

import java.util.Set;

/**
 * Enumeration defining the scopes of data access that can be granted through consent.
 * Aligned with UAE CBUAE Open Finance API specifications.
 */
public enum ConsentScope {
    /**
     * Access to basic account information (account numbers, types, balances).
     */
    ACCOUNT_INFORMATION("accounts"),
    
    /**
     * Access to transaction history and details.
     */
    TRANSACTION_HISTORY("transactions"),
    
    /**
     * Access to loan details and schedules.
     */
    LOAN_DETAILS("loans"),
    
    /**
     * Access to payment initiation capabilities.
     */
    PAYMENT_INITIATION("payments"),
    
    /**
     * Access to Islamic finance specific information (Murabaha, Ijara, etc.).
     */
    ISLAMIC_FINANCE_DETAILS("islamic_finance"),
    
    /**
     * Access to customer profile and KYC information.
     */
    CUSTOMER_PROFILE("profile"),
    
    /**
     * Access to investment and wealth management information.
     */
    INVESTMENT_DETAILS("investments");
    
    private final String value;
    
    ConsentScope(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
    
    /**
     * Creates ConsentScope from string value.
     */
    public static ConsentScope fromValue(String value) {
        for (ConsentScope scope : values()) {
            if (scope.value.equalsIgnoreCase(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unknown consent scope: " + value);
    }
    
    /**
     * Gets all scopes that are considered sensitive and require additional security.
     */
    public static Set<ConsentScope> getSensitiveScopes() {
        return Set.of(
                TRANSACTION_HISTORY,
                PAYMENT_INITIATION,
                CUSTOMER_PROFILE,
                INVESTMENT_DETAILS
        );
    }
    
    /**
     * Gets all scopes related to financial data.
     */
    public static Set<ConsentScope> getFinancialScopes() {
        return Set.of(
                ACCOUNT_INFORMATION,
                TRANSACTION_HISTORY,
                LOAN_DETAILS,
                ISLAMIC_FINANCE_DETAILS,
                INVESTMENT_DETAILS
        );
    }
    
    /**
     * Checks if this scope is considered sensitive.
     */
    public boolean isSensitive() {
        return getSensitiveScopes().contains(this);
    }
    
    /**
     * Checks if this scope relates to financial data.
     */
    public boolean isFinancial() {
        return getFinancialScopes().contains(this);
    }
    
    @Override
    public String toString() {
        return value;
    }
}