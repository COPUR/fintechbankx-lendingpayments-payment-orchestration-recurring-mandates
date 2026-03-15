package com.enterprise.openfinance.infrastructure.monitoring.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ComplianceStatistics {
    private final int violationCount;
    
    public boolean hasViolations() {
        return violationCount > 0;
    }
}