package com.enterprise.openfinance.domain.model.consent;

/**
 * Enumeration representing the status of a consent in the Open Finance lifecycle.
 * Follows UAE CBUAE Open Finance regulations for consent management.
 */
public enum ConsentStatus {
    /**
     * Initial state when consent is created but not yet authorized by customer.
     */
    PENDING,
    
    /**
     * Consent has been authorized by the customer and can be used for data access.
     */
    AUTHORIZED,
    
    /**
     * Consent has been explicitly revoked by the customer.
     */
    REVOKED,
    
    /**
     * Consent has expired based on the configured expiry date.
     */
    EXPIRED,
    
    /**
     * Consent was rejected by the customer during authorization.
     */
    REJECTED;
    
    /**
     * Checks if the consent status allows data access.
     */
    public boolean allowsDataAccess() {
        return this == AUTHORIZED;
    }
    
    /**
     * Checks if the consent can be authorized from current status.
     */
    public boolean canBeAuthorized() {
        return this == PENDING;
    }
    
    /**
     * Checks if the consent can be revoked from current status.
     */
    public boolean canBeRevoked() {
        return this == PENDING || this == AUTHORIZED;
    }
    
    /**
     * Checks if the consent is in a terminal state (cannot be changed).
     */
    public boolean isTerminal() {
        return this == REVOKED || this == EXPIRED || this == REJECTED;
    }
}