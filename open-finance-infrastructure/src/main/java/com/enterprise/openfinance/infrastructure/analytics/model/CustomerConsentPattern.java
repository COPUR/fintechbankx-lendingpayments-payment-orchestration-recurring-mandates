package com.enterprise.openfinance.infrastructure.analytics.model;

import com.enterprise.openfinance.domain.model.consent.ConsentScope;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Customer consent behavioral patterns for analytics.
 * Helps understand customer preferences while maintaining privacy.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "customer_patterns")
public class CustomerConsentPattern {
    
    @Id
    private String id;
    
    @Indexed(unique = true)
    private String customerId; // Masked for privacy
    
    private Long totalConsents;
    
    private Map<String, Long> participantConsents; // participant -> count
    
    private List<ConsentScope> recentScopes; // Last 10 scopes
    
    @Indexed
    private Instant firstConsentDate;
    
    @Indexed
    private Instant lastConsentDate;
    
    private Double averageConsentDuration; // in days
    
    private Long totalRevocations;
    
    @Transient
    private String preferredParticipant;
    
    @Transient
    private List<String> frequentScopes;
    
    // Behavioral insights
    private String consentBehaviorPattern; // CONSERVATIVE, MODERATE, LIBERAL
    private Double trustScore; // 0.0 to 1.0
    private String riskProfile; // LOW, MEDIUM, HIGH

    public String getPreferredParticipant() {
        if (participantConsents == null || participantConsents.isEmpty()) {
            return null;
        }
        return participantConsents.entrySet().stream()
            .max(Map.Entry.<String, Long>comparingByValue()
                .thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey)
            .orElse(null);
    }

    public List<String> getFrequentScopes() {
        if (recentScopes == null || recentScopes.isEmpty()) {
            return List.of();
        }
        return recentScopes.stream()
            .filter(Objects::nonNull)
            .map(ConsentScope::name)
            .collect(Collectors.groupingBy(scope -> scope, Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(3)
            .map(Map.Entry::getKey)
            .toList();
    }
    
    public static CustomerConsentPattern empty(String customerId) {
        return CustomerConsentPattern.builder()
            .customerId(customerId)
            .totalConsents(0L)
            .participantConsents(Map.of())
            .recentScopes(List.of())
            .totalRevocations(0L)
            .averageConsentDuration(0.0)
            .trustScore(0.5)
            .riskProfile("UNKNOWN")
            .consentBehaviorPattern("UNKNOWN")
            .build();
    }
}
