package com.enterprise.openfinance.domain.model.consent;

/**
 * Enumeration defining the business purposes for which consent can be granted.
 * Aligned with UAE CBUAE Open Finance regulatory requirements for purpose limitation.
 */
public enum ConsentPurpose {
    /**
     * Account information services - basic account data access.
     */
    ACCOUNT_INFORMATION("Account Information Services"),
    
    /**
     * Payment initiation services.
     */
    PAYMENT_INITIATION("Payment Initiation Services"),
    
    /**
     * Loan application and processing.
     */
    LOAN_APPLICATION("Loan Application Processing"),
    
    /**
     * Credit assessment and scoring.
     */
    CREDIT_ASSESSMENT("Credit Assessment"),
    
    /**
     * Financial advisory services.
     */
    FINANCIAL_ADVISORY("Financial Advisory Services"),
    
    /**
     * Investment services and wealth management.
     */
    INVESTMENT_SERVICES("Investment Services"),
    
    /**
     * Islamic finance product offerings.
     */
    ISLAMIC_FINANCE("Islamic Finance Services"),
    
    /**
     * Insurance and takaful services.
     */
    INSURANCE_SERVICES("Insurance Services"),
    
    /**
     * Regulatory compliance and reporting.
     */
    COMPLIANCE_REPORTING("Compliance Reporting"),
    
    /**
     * Fraud detection and prevention.
     */
    FRAUD_PREVENTION("Fraud Prevention"),
    
    /**
     * Customer due diligence and KYC.
     */
    CUSTOMER_DUE_DILIGENCE("Customer Due Diligence");
    
    private final String description;
    
    ConsentPurpose(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * Creates ConsentPurpose from string value.
     */
    public static ConsentPurpose fromValue(String value) {
        for (ConsentPurpose purpose : values()) {
            if (purpose.name().equalsIgnoreCase(value) || 
                purpose.description.equalsIgnoreCase(value)) {
                return purpose;
            }
        }
        throw new IllegalArgumentException("Unknown consent purpose: " + value);
    }
    
    /**
     * Checks if this purpose requires extended consent validity.
     */
    public boolean requiresExtendedValidity() {
        return this == LOAN_APPLICATION || 
               this == INVESTMENT_SERVICES || 
               this == ISLAMIC_FINANCE;
    }
    
    /**
     * Checks if this purpose allows payment operations.
     */
    public boolean allowsPaymentOperations() {
        return this == PAYMENT_INITIATION;
    }
    
    /**
     * Gets the recommended consent validity period in days for this purpose.
     */
    public int getRecommendedValidityDays() {
        return switch (this) {
            case PAYMENT_INITIATION -> 1;  // Single use
            case FRAUD_PREVENTION, COMPLIANCE_REPORTING -> 365;  // Extended
            case LOAN_APPLICATION, INVESTMENT_SERVICES -> 180;  // Long term
            case ISLAMIC_FINANCE, CUSTOMER_DUE_DILIGENCE -> 90;  // Medium term
            default -> 30;  // Standard
        };
    }
    
    @Override
    public String toString() {
        return description;
    }
}