package com.enterprise.openfinance.application.saga.model;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents the execution state of a distributed saga.
 * Contains all information needed for saga coordination, compensation, and recovery.
 */
@Data
@Builder(toBuilder = true)
public class SagaExecution {
    
    private final SagaId sagaId;
    private SagaStatus status;
    private final Object sagaData;
    
    // Timing information
    private final Instant startedAt;
    private Instant completedAt;
    private Instant abortedAt;
    private Instant compensationStartedAt;
    private Instant compensationCompletedAt;
    
    // Steps and compensations
    @Singular
    private List<SagaStep> steps;
    
    // Compensations in execution order (LinkedHashMap preserves insertion order)
    @Builder.Default
    private LinkedHashMap<String, Runnable> compensations = new LinkedHashMap<>();
    
    // Context data shared between steps
    @Builder.Default
    private Map<String, Object> contextData = new ConcurrentHashMap<>();
    
    // Failure information
    private String abortReason;
    private String lastError;
    
    /**
     * Add a step to the saga execution.
     */
    public void addStep(SagaStep step) {
        this.steps.add(step);
    }
    
    /**
     * Mark a step as completed and update saga status.
     */
    public SagaExecution markStepCompleted(String stepName) {
        var step = findStepByName(stepName);
        if (step != null) {
            step.setStatus(StepStatus.COMPLETED);
            step.setCompletedAt(Instant.now());
        }
        
        // Update saga status if all steps are completed
        if (areAllStepsCompleted()) {
            this.status = SagaStatus.COMPLETED;
            this.completedAt = Instant.now();
        } else {
            this.status = SagaStatus.EXECUTING;
        }
        
        return this;
    }
    
    /**
     * Add compensation for a step.
     */
    public void addCompensation(String stepName, Runnable compensation) {
        compensations.put(stepName, compensation);
    }
    
    /**
     * Add context data for sharing between steps.
     */
    public void addContextData(String key, Object value) {
        contextData.put(key, value);
    }
    
    /**
     * Get context data.
     */
    public Object getContextData(String key) {
        return contextData.get(key);
    }
    
    /**
     * Get the names of all completed steps.
     */
    public List<String> getCompletedSteps() {
        return steps.stream()
            .filter(step -> step.getStatus() == StepStatus.COMPLETED)
            .map(SagaStep::getStepName)
            .toList();
    }
    
    /**
     * Get the names of all failed steps.
     */
    public List<String> getFailedSteps() {
        return steps.stream()
            .filter(step -> step.getStatus() == StepStatus.FAILED)
            .map(SagaStep::getStepName)
            .toList();
    }
    
    /**
     * Calculate total execution time.
     */
    public Duration getExecutionTime() {
        var endTime = completedAt != null ? completedAt : 
                     compensationCompletedAt != null ? compensationCompletedAt :
                     abortedAt != null ? abortedAt :
                     Instant.now();
        
        return Duration.between(startedAt, endTime);
    }
    
    /**
     * Check if the saga is in a terminal state.
     */
    public boolean isTerminal() {
        return status == SagaStatus.COMPLETED ||
               status == SagaStatus.COMPENSATED ||
               status == SagaStatus.ABORTED;
    }
    
    /**
     * Check if the saga can be compensated.
     */
    public boolean canBeCompensated() {
        return status != SagaStatus.COMPLETED &&
               status != SagaStatus.COMPENSATED &&
               status != SagaStatus.ABORTED &&
               !compensations.isEmpty();
    }
    
    /**
     * Get saga execution summary.
     */
    public SagaExecutionSummary getSummary() {
        return SagaExecutionSummary.builder()
            .sagaId(sagaId)
            .status(status)
            .totalSteps(steps.size())
            .completedSteps(getCompletedSteps().size())
            .failedSteps(getFailedSteps().size())
            .executionTime(getExecutionTime())
            .hasCompensations(!compensations.isEmpty())
            .startedAt(startedAt)
            .completedAt(completedAt)
            .build();
    }
    
    private SagaStep findStepByName(String stepName) {
        return steps.stream()
            .filter(step -> stepName.equals(step.getStepName()))
            .findFirst()
            .orElse(null);
    }
    
    private boolean areAllStepsCompleted() {
        return steps.stream()
            .allMatch(step -> step.getStatus() == StepStatus.COMPLETED);
    }
}