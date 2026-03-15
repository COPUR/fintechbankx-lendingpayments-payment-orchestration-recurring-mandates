package com.enterprise.openfinance.application.saga.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Strong-typed identifier for saga executions.
 * Provides uniqueness and type safety for saga identification.
 */
public final class SagaId {

    private final String value;

    private SagaId(String value) {
        this.value = value;
    }

    /**
     * Generate a new unique saga ID.
     */
    public static SagaId generate() {
        return new SagaId("SAGA-" + UUID.randomUUID().toString().toUpperCase());
    }

    /**
     * Create from a string value.
     */
    public static SagaId of(String value) {
        Objects.requireNonNull(value, "Saga ID value cannot be null");
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException("Saga ID value cannot be empty");
        }
        return new SagaId(value);
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
        if (!(o instanceof SagaId other))
            return false;
        return Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}