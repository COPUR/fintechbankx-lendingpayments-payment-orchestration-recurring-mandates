package com.enterprise.openfinance.domain.event;

// Re-export the shared domain event interface for convenience
public interface DomainEvent extends com.enterprise.shared.domain.event.DomainEvent {
    // This interface extends the shared kernel DomainEvent interface
    // Allows the open finance context to add domain-specific event methods if needed
}