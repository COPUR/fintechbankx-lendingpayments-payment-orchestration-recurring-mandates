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

/**
 * Usage analytics record for consent data access.
 * Contains detailed information about each data access event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "usage_analytics")
@CompoundIndexes({
    @CompoundIndex(name = "participant_date_idx", def = "{'participantId': 1, 'date': 1}"),
    @CompoundIndex(name = "consent_timestamp_idx", def = "{'consentId': 1, 'timestamp': -1}")
})
public class UsageAnalytics {
    
    @Id
    private String id;
    
    @Indexed
    private String consentId;
    
    @Indexed
    private String participantId;
    
    private String customerId; // Masked for privacy
    
    private String accessType;
    
    private String scope;
    
    private String dataRequested; // Masked for privacy
    
    @Indexed
    private LocalDate date;
    
    @Indexed
    private Instant timestamp;
    
    private Long processingTimeMs;
    
    private String responseStatus;
    
    private Long dataSizeBytes;
    
    private String apiEndpoint;
    
    private String userAgent;
    
    private String sourceIp; // Masked for privacy
}