package com.enterprise.openfinance.application.saga.model;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable summary of a distributed saga execution.
 * Returned by {@link SagaExecution#getSummary()} for reporting and audit
 * purposes.
 */
@Value
@Builder
public class SagaExecutionSummary {

    SagaId sagaId;
    SagaStatus status;
    int totalSteps;
    int completedSteps;
    int failedSteps;
    Duration executionTime;
    boolean hasCompensations;
    Instant startedAt;
    Instant completedAt;

    /**
     * Percentage of steps that completed successfully (0–100).
     */
    public double completionPercentage() {
        if (totalSteps == 0)
            return 0.0;
        return (completedSteps * 100.0) / totalSteps;
    }

    /**
     * Whether the saga finished without any step failures.
     */
    public boolean isFullySuccessful() {
        return status == SagaStatus.COMPLETED && failedSteps == 0;
    }

    /**
     * Whether the saga is still running.
     */
    public boolean isInProgress() {
        return status == SagaStatus.STARTED || status == SagaStatus.EXECUTING;
    }
}
