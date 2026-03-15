package com.enterprise.openfinance.infrastructure.adapter.input.rest.model;

import com.enterprise.openfinance.domain.model.consent.ConsentId;
import com.enterprise.openfinance.domain.model.consent.ConsentScope;
import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import com.enterprise.shared.domain.CustomerId;
import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.Set;

/**
 * Data sharing request for cross-platform data aggregation.
 * Used by saga orchestration to coordinate data retrieval across
 * Enterprise Loan Management, AmanahFi Platform, and Masrufi Framework.
 */
@Data
@Builder
public class DataSharingRequest {
    
    private final DataRequestId requestId;
    private final ConsentId consentId;
    private final CustomerId customerId;
    private final ParticipantId participantId;
    
    private final Set<ConsentScope> requestedScopes;
    private final DataFormat dataFormat;
    private final String encryptionMethod;
    
    // Delivery options
    private final String deliveryEndpoint;
    private final String deliveryMethod;
    private final String callbackUrl;
    private final String participantPublicKey;
    
    // Request metadata
    private final Map<String, Object> filterCriteria;
    private final Map<String, String> complianceRequirements;
    private final Long dataSize;
}