package com.enterprise.openfinance.infrastructure.monitoring.model;

import com.enterprise.openfinance.domain.model.consent.ConsentId;
import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import com.enterprise.shared.domain.CustomerId;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
public class ComplianceCheckResult {
    private final String checkId;
    private final ConsentId consentId;
    private final ParticipantId participantId;
    private final CustomerId customerId;
    private final ComplianceCheckType checkType;
    private final Instant startedAt;
    private Instant completedAt;
    private double overallScore;
    private ComplianceStatus complianceStatus;
    private String errorMessage;
    private final Map<String, ComplianceCheckDetail> checkResults = new HashMap<>();
    
    public void addCheckResult(String checkName, ComplianceCheckDetail detail) {
        checkResults.put(checkName, detail);
    }
}

enum ComplianceCheckType {
    CBUAE_REGULATION,
    PCI_DSS_V4,
    FAPI_COMPLIANCE
}

enum ComplianceStatus {
    COMPLIANT,
    NON_COMPLIANT_MINOR,
    NON_COMPLIANT_MAJOR,
    ERROR
}