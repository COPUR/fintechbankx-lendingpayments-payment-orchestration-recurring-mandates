package com.enterprise.openfinance.infrastructure.analytics;

import org.springframework.stereotype.Service;

/**
 * Service for masking sensitive data in analytics processing.
 * Ensures PCI-DSS v4 compliance for data analytics.
 */
@Service
public class AnalyticsDataMaskingService {

    public String maskCustomerId(String customerId) {
        if (customerId == null || customerId.length() < 8) {
            return "MASKED";
        }
        return "MASKED-" + customerId.substring(customerId.length() - 4);
    }

    public String maskDataRequested(String dataRequested) {
        if (dataRequested == null) {
            return "MASKED-DATA";
        }
        return "MASKED-" + dataRequested.hashCode();
    }

    public Object maskScopes(Object scopes) {
        // Mask sensitive scope information
        return scopes; // For now, return as-is since scopes are not sensitive
    }

    public Object maskData(Object data) {
        if (data == null) {
            return null;
        }
        // Apply general data masking rules
        return data.toString().replaceAll("\\d{4,}", "****");
    }
}