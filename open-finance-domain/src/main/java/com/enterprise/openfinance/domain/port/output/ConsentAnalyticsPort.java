package com.enterprise.openfinance.domain.port.output;

import com.enterprise.openfinance.infrastructure.analytics.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Port interface for consent analytics operations.
 * This follows hexagonal architecture - the domain defines the interface,
 * infrastructure implements it.
 */
public interface ConsentAnalyticsPort {
    
    CompletableFuture<ConsentMetricsSummary> getConsentMetrics(String participantId, 
                                                               LocalDate fromDate, 
                                                               LocalDate toDate);
    
    CompletableFuture<List<UsageAnalytics>> getUsageAnalytics(String participantId,
                                                              String scope,
                                                              LocalDate fromDate, 
                                                              LocalDate toDate);
    
    CompletableFuture<CustomerConsentPattern> getCustomerConsentPattern(String customerId);
    
    CompletableFuture<ComplianceReport> generateComplianceReport(LocalDate reportDate);
    
    CompletableFuture<List<SecurityIncident>> getSecurityIncidents(LocalDate fromDate,
                                                                   LocalDate toDate,
                                                                   String severity);
    
    CompletableFuture<Map<String, Object>> getRealTimeMetrics();
}