package com.enterprise.openfinance.application.saga.model;

/**
 * Enumeration of saga step execution states.
 * Defines the lifecycle states of an individual saga step.
 */
public enum StepStatus {
    
    /**
     * Step is currently executing.
     */
    EXECUTING,
    
    /**
     * Step completed successfully.
     */
    COMPLETED,
    
    /**
     * Step failed during execution.
     */
    FAILED,
    
    /**
     * Step was compensated (rolled back).
     */
    COMPENSATED;
    
    /**
     * Check if the step is in a terminal state.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == COMPENSATED;
    }
    
    /**
     * Check if the step is currently active.
     */
    public boolean isActive() {
        return this == EXECUTING;
    }
    
    /**
     * Check if the step completed successfully.
     */
    public boolean isSuccessful() {
        return this == COMPLETED;
    }
    
    /**
     * Check if the step failed.
     */
    public boolean isFailed() {
        return this == FAILED;
    }
    
    /**
     * Check if the step was compensated.
     */
    public boolean wasCompensated() {
        return this == COMPENSATED;
    }
}