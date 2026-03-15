package com.enterprise.openfinance.infrastructure.monitoring.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SecurityViolation {
    private final String violationType;
    private final String severity;
    private final String description;
}