package com.enterprise.openfinance.application.saga.model;

/**
 * Enumeration of saga execution states.
 * Defines the lifecycle states of a distributed saga.
 */
public enum SagaStatus {
    
    /**
     * Saga has been initiated but not yet executing steps.
     */
    STARTED,
    
    /**
     * Saga is currently executing steps.
     */
    EXECUTING,
    
    /**
     * All saga steps completed successfully.
     */
    COMPLETED,
    
    /**
     * Saga failed and is executing compensations.
     */
    COMPENSATING,
    
    /**
     * All compensations have been executed (saga rolled back).
     */
    COMPENSATED,
    
    /**
     * Saga was explicitly aborted without compensation.
     */
    ABORTED;
    
    /**
     * Check if the saga is in a terminal state (no further transitions possible).
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == COMPENSATED || this == ABORTED;
    }
    
    /**
     * Check if the saga is currently active (can execute steps).
     */
    public boolean isActive() {
        return this == STARTED || this == EXECUTING;
    }
    
    /**
     * Check if the saga has failed and requires compensation.
     */
    public boolean requiresCompensation() {
        return this == COMPENSATING;
    }
    
    /**
     * Check if the saga completed successfully.
     */
    public boolean isSuccessful() {
        return this == COMPLETED;
    }
    
    /**
     * Check if the saga was rolled back.
     */
    public boolean wasRolledBack() {
        return this == COMPENSATED;
    }
}