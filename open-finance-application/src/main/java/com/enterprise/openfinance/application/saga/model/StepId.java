package com.enterprise.openfinance.application.saga.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Strong-typed identifier for individual saga steps.
 */
public final class StepId {

    private final String value;

    private StepId(String value) {
        this.value = value;
    }

    /**
     * Generate a new unique step ID.
     */
    public static StepId generate() {
        return new StepId("STEP-" + UUID.randomUUID().toString().toUpperCase());
    }

    /**
     * Create from a string value.
     */
    public static StepId of(String value) {
        Objects.requireNonNull(value, "Step ID value cannot be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("Step ID value cannot be empty");
        }
        return new StepId(value);
    }

    /**
     * Create a step ID for a named step within a saga.
     */
    public static StepId forStep(String sagaId, String stepName) {
        Objects.requireNonNull(sagaId, "Saga ID cannot be null");
        Objects.requireNonNull(stepName, "Step name cannot be null");
        return new StepId(sagaId + ":" + stepName);
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof StepId other))
            return false;
        return Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}
