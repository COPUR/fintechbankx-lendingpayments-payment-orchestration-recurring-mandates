package com.enterprise.openfinance.infrastructure.analytics.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Daily compliance report for regulatory requirements.
 * Provides comprehensive compliance status and metrics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "compliance_reports")
@CompoundIndexes({
    @CompoundIndex(name = "report_date_type_idx", def = "{'reportDate': 1, 'reportType': 1}", unique = true)
})
public class ComplianceReport {
    
    @Id
    private String id;
    
    @Indexed
    private LocalDate reportDate;
    
    @Indexed
    private Instant generatedAt;
    
    private ReportType reportType;
    
    private ComplianceMetrics consentMetrics;
    
    private List<ComplianceViolation> violations;
    
    private Double complianceScore; // 0.0 to 100.0
    
    private Map<String, Boolean> regulatoryChecks;
    
    private ReportStatus status;
    
    private String reviewedBy;
    
    private Instant reviewedAt;
    
    // CBUAE specific fields
    private String cbuaeReportId;
    private Boolean submittedToCBUAE;
    private Instant submissionDate;

    public enum ReportType {
        DAILY_COMPLIANCE,
        WEEKLY_SUMMARY,
        MONTHLY_SUMMARY
    }

    public enum ReportStatus {
        GENERATED,
        REVIEWED,
        SUBMITTED
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplianceMetrics {
        private Long totalConsents;
        private Long validConsents;
        private Long expiredConsents;
        private Long revokedConsents;
        private Long dataAccessEvents;
        private Double averageResponseTime;
        private Long securityIncidents;
        private Double uptimePercentage;
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplianceViolation {
        private String violationType;
        private String severity;
        private String description;
        private Instant detectedAt;
        private String affectedEntity;
        private String remedialAction;
        private String status;
    }
}
