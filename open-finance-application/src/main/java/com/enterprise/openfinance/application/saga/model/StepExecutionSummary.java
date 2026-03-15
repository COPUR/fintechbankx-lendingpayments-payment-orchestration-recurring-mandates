package com.enterprise.openfinance.application.saga.model;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;
import java.time.Instant;

/**
 * Immutable summary of a single saga step execution.
 * Returned by {@link SagaStep#getSummary()} for reporting and audit purposes.
 */
@Value
@Builder
public class StepExecutionSummary {

    StepId stepId;
    String stepName;
    StepStatus status;
    Duration executionTime;
    boolean hasError;
    boolean hasCompensation;
    Instant startedAt;
    Instant completedAt;
    Instant failedAt;

    /**
     * Whether the step completed successfully.
     */
    public boolean isSuccessful() {
        return status == StepStatus.COMPLETED;
    }

    /**
     * Whether the step is in a terminal state.
     */
    public boolean isTerminal() {
        return status == StepStatus.COMPLETED
                || status == StepStatus.FAILED
                || status == StepStatus.COMPENSATED;
    }
}
