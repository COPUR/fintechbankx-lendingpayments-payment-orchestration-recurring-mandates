package com.enterprise.openfinance.infrastructure.monitoring.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ComplianceCheckDetail {
    private final String checkName;
    private final boolean passed;
    private final double score;
    private final String details;
}