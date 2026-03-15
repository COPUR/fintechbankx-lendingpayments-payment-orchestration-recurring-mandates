package com.enterprise.openfinance.infrastructure.analytics.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * Security incident record for compliance and monitoring.
 * Tracks suspicious activities and security violations.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "security_incidents")
public class SecurityIncident {
    
    @Id
    private String id;
    
    @Indexed
    private String incidentType;
    
    @Indexed
    private Severity severity;
    
    private String description;
    
    private Map<String, String> details;
    
    @Indexed
    private Instant occurredAt;
    
    @Indexed
    private IncidentStatus status;
    
    private String assignedTo;
    
    private String resolution;
    
    private Instant resolvedAt;
    
    // Compliance fields
    private String complianceImpact;
    private String notificationsSent;
    private String externalReporting;

    public enum Severity {
        HIGH,
        MEDIUM,
        LOW
    }

    public enum IncidentStatus {
        OPEN,
        INVESTIGATING,
        RESOLVED,
        CLOSED
    }
}
