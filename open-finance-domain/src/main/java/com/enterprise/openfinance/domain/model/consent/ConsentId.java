package com.enterprise.openfinance.domain.model.consent;

import com.enterprise.shared.domain.ValueObject;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.UUID;

/**
 * Value object representing a unique consent identifier.
 * Immutable and implements proper value object semantics.
 */
@Getter
@EqualsAndHashCode
@ValueObject
public final class ConsentId {
    
    private final String value;
    
    private ConsentId(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Consent ID cannot be null or empty");
        }
        this.value = value.trim();
    }
    
    /**
     * Creates a new ConsentId from a string value.
     */
    public static ConsentId of(String value) {
        return new ConsentId(value);
    }
    
    /**
     * Generates a new unique ConsentId.
     */
    public static ConsentId generate() {
        return new ConsentId("CONSENT-" + UUID.randomUUID().toString().toUpperCase());
    }
    
    @Override
    public String toString() {
        return value;
    }
}