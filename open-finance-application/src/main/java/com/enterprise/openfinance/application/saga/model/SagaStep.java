package com.enterprise.openfinance.application.saga.model;

import lombok.Builder;
import lombok.Data;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Represents an individual step within a saga execution.
 * Contains timing, status, and error information for the step.
 */
@Data
@Builder(toBuilder = true)
public class SagaStep {
    
    private final StepId stepId;
    private final String stepName;
    private StepStatus status;
    
    // Timing information
    private final Instant startedAt;
    private Instant completedAt;
    private Instant failedAt;
    
    // Error information
    private String errorMessage;
    private String errorCode;
    private Exception exception;
    
    // Step metadata
    private Map<String, Object> metadata;
    
    // Compensation information
    private boolean hasCompensation;
    private Instant compensatedAt;
    private String compensationError;
    
    /**
     * Calculate step execution time.
     */
    public Duration getExecutionTime() {
        var endTime = completedAt != null ? completedAt :
                     failedAt != null ? failedAt :
                     Instant.now();
        
        return Duration.between(startedAt, endTime);
    }
    
    /**
     * Check if the step is in a terminal state.
     */
    public boolean isTerminal() {
        return status == StepStatus.COMPLETED || 
               status == StepStatus.FAILED ||
               status == StepStatus.COMPENSATED;
    }
    
    /**
     * Check if the step was successful.
     */
    public boolean isSuccessful() {
        return status == StepStatus.COMPLETED;
    }
    
    /**
     * Check if the step failed.
     */
    public boolean isFailed() {
        return status == StepStatus.FAILED;
    }
    
    /**
     * Mark step as compensated.
     */
    public void markCompensated() {
        this.status = StepStatus.COMPENSATED;
        this.compensatedAt = Instant.now();
    }
    
    /**
     * Mark step as compensation failed.
     */
    public void markCompensationFailed(String error) {
        this.compensationError = error;
    }
    
    /**
     * Get step execution summary.
     */
    public StepExecutionSummary getSummary() {
        return StepExecutionSummary.builder()
            .stepId(stepId)
            .stepName(stepName)
            .status(status)
            .executionTime(getExecutionTime())
            .hasError(errorMessage != null)
            .hasCompensation(hasCompensation)
            .startedAt(startedAt)
            .completedAt(completedAt)
            .failedAt(failedAt)
            .build();
    }
}