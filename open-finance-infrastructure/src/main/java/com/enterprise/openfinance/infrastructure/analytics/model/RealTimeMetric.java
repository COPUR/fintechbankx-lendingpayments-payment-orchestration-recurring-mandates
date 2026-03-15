package com.enterprise.openfinance.infrastructure.analytics.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Real-time metrics for Open Finance operations.
 * Used for live monitoring and alerting.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "real_time_metrics")
public class RealTimeMetric {
    
    @Id
    private String id;
    
    @Indexed
    private String metricType;
    
    @Indexed
    private String participantId;
    
    private Long value;
    
    @Indexed(expireAfterSeconds = 300) // TTL: 5 minutes
    private Instant timestamp;
    
    // Additional fields for detailed metrics
    private String customerId;
    private String consentId;
    private String scope;
    private Double processingTimeMs;
    private String status;
}