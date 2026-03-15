package com.enterprise.openfinance.domain.model.participant;

import com.bank.shared.kernel.domain.ValueObject;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Value object representing a unique CBUAE-registered participant identifier.
 * Follows UAE CBUAE participant directory standards.
 */
@Getter
@EqualsAndHashCode
@ValueObject
public final class ParticipantId {
    
    private final String value;
    
    private ParticipantId(String value) {
        validateParticipantId(value);
        this.value = value.trim();
    }
    
    /**
     * Creates a new ParticipantId from a string value.
     */
    public static ParticipantId of(String value) {
        return new ParticipantId(value);
    }
    
    private void validateParticipantId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Participant ID cannot be null or empty");
        }
        
        String trimmed = value.trim();
        
        // CBUAE participant IDs should follow specific format
        if (trimmed.length() < 8 || trimmed.length() > 20) {
            throw new IllegalArgumentException("Participant ID must be between 8 and 20 characters");
        }
        
        // Should contain only alphanumeric characters and hyphens
        if (!trimmed.matches("^[A-Z0-9\\-]+$")) {
            throw new IllegalArgumentException("Participant ID must contain only uppercase letters, numbers, and hyphens");
        }
    }
    
    @Override
    public String toString() {
        return value;
    }
}