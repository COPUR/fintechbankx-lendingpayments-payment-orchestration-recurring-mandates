package com.enterprise.openfinance.domain.model.consent;

import com.enterprise.openfinance.domain.event.ConsentAuthorizedEvent;
import com.enterprise.openfinance.domain.event.ConsentCreatedEvent;
import com.enterprise.openfinance.domain.event.ConsentExpiredEvent;
import com.enterprise.openfinance.domain.event.ConsentRevokedEvent;
import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import com.enterprise.shared.domain.AggregateRoot;
import com.enterprise.shared.domain.CustomerId;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Consent aggregate root representing customer consent for data sharing in Open Finance.
 * Follows DDD principles and implements the consent lifecycle as per UAE Open Finance regulations.
 * 
 * Business Rules:
 * - Consent must be explicitly granted by customer
 * - Consent can only be used when AUTHORIZED and not expired
 * - Consent can be revoked by customer at any time
 * - Expired consent cannot be used for data access
 * - Consent renewal requires customer re-authorization
 */
@Getter
@AggregateRoot
public class Consent extends com.enterprise.shared.domain.AggregateRoot<ConsentId> {

    private final ConsentId id;
    private final CustomerId customerId;
    private final ParticipantId participantId;
    private final Set<ConsentScope> scopes;
    private final ConsentPurpose purpose;
    private final LocalDateTime createdAt;
    
    private ConsentStatus status;
    private LocalDateTime expiryDate;
    private LocalDateTime authorizedAt;
    private LocalDateTime revokedAt;
    private String revocationReason;
    private LocalDateTime renewedAt;

    @Builder
    public Consent(
            ConsentId id,
            CustomerId customerId,
            ParticipantId participantId,
            Set<ConsentScope> scopes,
            ConsentPurpose purpose,
            LocalDateTime expiryDate) {
        
        // Validate required fields
        validateRequiredFields(id, customerId, participantId, scopes, purpose, expiryDate);
        
        this.id = id;
        this.customerId = customerId;
        this.participantId = participantId;
        this.scopes = Set.copyOf(scopes); // Immutable copy
        this.purpose = purpose;
        this.expiryDate = expiryDate;
        this.createdAt = LocalDateTime.now();
        this.status = ConsentStatus.PENDING;
        
        // Emit domain event
        addDomainEvent(new ConsentCreatedEvent(
                this.id,
                this.customerId,
                this.participantId,
                this.scopes,
                this.purpose,
                this.createdAt
        ));
    }

    /**
     * Authorizes the consent, changing status from PENDING to AUTHORIZED.
     * Business rule: Only pending consents can be authorized.
     */
    public void authorize() {
        if (status != ConsentStatus.PENDING) {
            throw new IllegalStateException(
                    String.format("Cannot authorize consent that is not pending. Current status: %s", status)
            );
        }
        
        this.status = ConsentStatus.AUTHORIZED;
        this.authorizedAt = LocalDateTime.now();
        
        addDomainEvent(new ConsentAuthorizedEvent(
                this.id,
                this.customerId,
                this.participantId,
                this.authorizedAt
        ));
    }

    /**
     * Revokes the consent with a reason.
     * Business rule: Only pending or authorized consents can be revoked.
     */
    public void revoke(String reason) {
        if (status == ConsentStatus.REVOKED) {
            throw new IllegalStateException("Consent is already revoked");
        }
        
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Revocation reason cannot be null or empty");
        }
        
        this.status = ConsentStatus.REVOKED;
        this.revokedAt = LocalDateTime.now();
        this.revocationReason = reason;
        
        addDomainEvent(new ConsentRevokedEvent(
                this.id,
                this.customerId,
                this.participantId,
                this.revokedAt,
                reason
        ));
    }

    /**
     * Renews the consent with a new expiry date.
     * Business rule: Only active (authorized and not expired/revoked) consents can be renewed.
     */
    public void renew(LocalDateTime newExpiryDate) {
        if (status == ConsentStatus.REVOKED) {
            throw new IllegalStateException("Cannot renew revoked consent");
        }
        
        if (status != ConsentStatus.AUTHORIZED) {
            throw new IllegalStateException("Cannot renew consent that is not authorized");
        }
        
        if (newExpiryDate == null || newExpiryDate.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("New expiry date must be in the future");
        }
        
        this.expiryDate = newExpiryDate;
        this.renewedAt = LocalDateTime.now();
    }

    /**
     * Marks the consent as expired.
     * This is typically called by a scheduled job or when expiry is detected.
     */
    public void markAsExpired() {
        if (status == ConsentStatus.AUTHORIZED && isExpired()) {
            this.status = ConsentStatus.EXPIRED;
            
            addDomainEvent(new ConsentExpiredEvent(
                    this.id,
                    this.customerId,
                    this.participantId,
                    LocalDateTime.now()
            ));
        }
    }

    /**
     * Checks if the consent is expired based on current time.
     */
    public boolean isExpired() {
        return expiryDate.isBefore(LocalDateTime.now());
    }

    /**
     * Checks if the consent is currently active (authorized and not expired or revoked).
     * Business rule: Active consent can be used for data sharing.
     */
    public boolean isActive() {
        return status == ConsentStatus.AUTHORIZED && !isExpired();
    }

    /**
     * Checks if the consent allows access to a specific scope.
     */
    public boolean hasScope(ConsentScope scope) {
        return scopes.contains(scope);
    }

    /**
     * Checks if the consent allows access to all specified scopes.
     */
    public boolean hasAllScopes(Set<ConsentScope> requiredScopes) {
        return scopes.containsAll(requiredScopes);
    }

    /**
     * Gets remaining validity in days.
     */
    public long getRemainingValidityDays() {
        if (isExpired()) {
            return 0;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), expiryDate);
    }

    /**
     * Checks if the consent is about to expire (within specified days).
     */
    public boolean isAboutToExpire(int days) {
        return !isExpired() && getRemainingValidityDays() <= days;
    }

    private void validateRequiredFields(
            ConsentId id,
            CustomerId customerId,
            ParticipantId participantId,
            Set<ConsentScope> scopes,
            ConsentPurpose purpose,
            LocalDateTime expiryDate) {
        
        if (id == null) {
            throw new IllegalArgumentException("Consent ID cannot be null");
        }
        if (customerId == null) {
            throw new IllegalArgumentException("Customer ID cannot be null");
        }
        if (participantId == null) {
            throw new IllegalArgumentException("Participant ID cannot be null");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("Consent scopes cannot be null or empty");
        }
        if (purpose == null) {
            throw new IllegalArgumentException("Consent purpose cannot be null");
        }
        if (expiryDate == null) {
            throw new IllegalArgumentException("Expiry date cannot be null");
        }
        if (expiryDate.isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("Expiry date cannot be in the past");
        }
    }

    @Override
    public ConsentId getId() {
        return id;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Consent consent = (Consent) obj;
        return id.equals(consent.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return String.format("Consent{id=%s, customerId=%s, participantId=%s, status=%s, scopes=%s, purpose=%s, expiryDate=%s}",
                id, customerId, participantId, status, scopes, purpose, expiryDate);
    }
}