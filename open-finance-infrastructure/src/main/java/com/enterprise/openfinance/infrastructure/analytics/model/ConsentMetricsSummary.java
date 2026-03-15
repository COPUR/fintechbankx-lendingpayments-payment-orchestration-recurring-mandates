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
 * Aggregated consent metrics summary for a participant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "consent_metrics_summary")
public class ConsentMetricsSummary {
    
    @Id
    private String id;
    
    @Indexed(unique = true)
    private String participantId;
    private Long totalConsents;
    private Long authorizedConsents;
    private Long revokedConsents;
    private Long expiredConsents;
    private Long totalUsage;
    private Double averageLifetime;
    private Instant generatedAt;
    
    public double getAuthorizationRate() {
        if (totalConsents == null || totalConsents == 0) {
            return 0.0;
        }
        return (authorizedConsents != null ? authorizedConsents : 0L) * 100.0 / totalConsents;
    }
    
    public static ConsentMetricsSummary empty(String participantId) {
        return ConsentMetricsSummary.builder()
            .participantId(participantId)
            .totalConsents(0L)
            .authorizedConsents(0L)
            .revokedConsents(0L)
            .expiredConsents(0L)
            .totalUsage(0L)
            .averageLifetime(0.0)
            .generatedAt(Instant.now())
            .build();
    }
}
