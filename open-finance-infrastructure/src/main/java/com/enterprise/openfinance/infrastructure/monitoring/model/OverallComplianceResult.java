package com.enterprise.openfinance.infrastructure.monitoring.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OverallComplianceResult {
    private final double score;
    private final ComplianceStatus status;
}