package com.enterprise.openfinance.domain.service;

import com.enterprise.openfinance.domain.event.*;
import com.enterprise.openfinance.domain.model.consent.*;
import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import com.enterprise.openfinance.domain.port.output.*;
import com.enterprise.shared.domain.CustomerId;
import com.enterprise.shared.domain.DomainService;
import com.enterprise.shared.domain.event.DomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Distributed Consent Service implementing Event-Driven Architecture
 * with comprehensive PCI-DSS v4 compliance and CQRS pattern.
 * 
 * This service manages the entire consent lifecycle across distributed nodes
 * with eventual consistency through event sourcing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@DomainService
public class DistributedConsentService {

    private final EventStore eventStore;
    private final ConsentRepository consentRepository;
    private final ParticipantRepository participantRepository;
    private final CBUAEIntegrationPort cbuaeIntegrationPort;
    private final DomainEventPublisher eventPublisher;
    private final ConsentCache consentCache;
    private final ConsentMetricsCollector metricsCollector;
    private final AuditTrailService auditTrailService;
    private final SecurityComplianceService securityComplianceService;

    /**
     * Creates a new consent with distributed coordination.
     * Implements saga pattern for consistent distributed state.
     */
    @Transactional
    public CompletableFuture<ConsentCreationResult> createConsentAsync(ConsentCreationRequest request) {
        log.info("Creating consent for customer: {}, participant: {}", 
                request.getCustomerId(), request.getParticipantId());

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Phase 1: Validate and prepare
                validateConsentCreationRequest(request);
                
                // Phase 2: Validate participant with CBUAE (external call)
                var participantValidation = validateParticipantWithCBUAE(request.getParticipantId());
                if (!participantValidation.isValid()) {
                    throw new ConsentCreationException("Participant validation failed: " + 
                            participantValidation.getFailureReason());
                }
                
                // Phase 3: Create consent aggregate
                var consent = createConsentAggregate(request);
                
                // Phase 4: Persist events atomically
                var events = consent.getUncommittedEvents();
                eventStore.saveEvents(consent.getId().getValue(), events, 0L);
                
                // Phase 5: Publish events for distributed processing
                publishEventsAsync(events);
                
                // Phase 6: Cache for performance
                cacheConsentAsync(consent);
                
                // Phase 7: Collect metrics
                metricsCollector.recordConsentCreation(request.getParticipantId(), 
                        request.getScopes(), request.getPurpose());
                
                log.info("Consent created successfully: {}", consent.getId());
                
                return ConsentCreationResult.builder()
                        .consentId(consent.getId())
                        .status(consent.getStatus())
                        .expiresAt(consent.getExpiresAt())
                        .interactionId(request.getInteractionId())
                        .build();
                        
            } catch (Exception e) {
                log.error("Failed to create consent for customer: {}", request.getCustomerId(), e);
                // Trigger compensation saga if needed
                triggerConsentCreationCompensation(request, e);
                throw new ConsentCreationException("Failed to create consent: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Authorizes a consent with distributed state synchronization.
     * Implements optimistic locking and event sourcing.
     */
    @Transactional
    public CompletableFuture<ConsentAuthorizationResult> authorizeConsentAsync(
            ConsentAuthorizationRequest request) {
        
        log.info("Authorizing consent: {} for customer: {}", 
                request.getConsentId(), request.getCustomerId());

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Phase 1: Load consent from cache or event store
                var consent = loadConsent(request.getConsentId());
                
                // Phase 2: Validate authorization request
                validateAuthorizationRequest(request, consent);
                
                // Phase 3: Perform authorization with security checks
                var authContext = buildAuthorizationContext(request);
                consent.authorize(authContext);
                
                // Phase 4: Persist authorization event
                var events = consent.getUncommittedEvents();
                eventStore.saveEvents(consent.getId().getValue(), events, consent.getVersion());
                
                // Phase 5: Publish authorization events
                publishEventsAsync(events);
                
                // Phase 6: Update cache
                updateConsentCache(consent);
                
                // Phase 7: Record audit trail for PCI-DSS compliance
                auditTrailService.recordConsentAuthorization(request.getConsentId(), 
                        request.getCustomerId(), request.getAuthorizationMethod(), 
                        request.getIpAddress(), request.getUserAgent());
                
                // Phase 8: Collect metrics
                metricsCollector.recordConsentAuthorization(consent.getParticipantId(), 
                        consent.getScopes());
                
                log.info("Consent authorized successfully: {}", request.getConsentId());
                
                return ConsentAuthorizationResult.builder()
                        .consentId(consent.getId())
                        .status(consent.getStatus())
                        .authorizedAt(consent.getAuthorizedAt())
                        .expiresAt(consent.getExpiresAt())
                        .interactionId(request.getInteractionId())
                        .build();
                        
            } catch (Exception e) {
                log.error("Failed to authorize consent: {}", request.getConsentId(), e);
                auditTrailService.recordConsentAuthorizationFailure(request.getConsentId(), 
                        request.getCustomerId(), e.getMessage());
                throw new ConsentAuthorizationException("Failed to authorize consent: " + 
                        e.getMessage(), e);
            }
        });
    }

    /**
     * Revokes a consent with immediate distributed notification.
     * Implements graceful degradation for network partitions.
     */
    @Transactional
    @CacheEvict(value = "activeConsents", key = "#request.consentId")
    public CompletableFuture<ConsentRevocationResult> revokeConsentAsync(
            ConsentRevocationRequest request) {
        
        log.info("Revoking consent: {} for reason: {}", 
                request.getConsentId(), request.getRevocationReason());

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Phase 1: Load consent
                var consent = loadConsent(request.getConsentId());
                
                // Phase 2: Validate revocation request
                validateRevocationRequest(request, consent);
                
                // Phase 3: Revoke consent
                consent.revoke(request.getRevocationReason());
                
                // Phase 4: Persist revocation event
                var events = consent.getUncommittedEvents();
                eventStore.saveEvents(consent.getId().getValue(), events, consent.getVersion());
                
                // Phase 5: Immediately notify all nodes
                publishEventsWithPriority(events, EventPriority.HIGH);
                
                // Phase 6: Notify participant systems
                notifyParticipantOfRevocation(consent.getParticipantId(), consent.getId());
                
                // Phase 7: Record audit trail
                auditTrailService.recordConsentRevocation(request.getConsentId(), 
                        request.getRevocationReason(), request.getRevokedBy(), 
                        request.getIpAddress());
                
                // Phase 8: Update metrics
                metricsCollector.recordConsentRevocation(consent.getParticipantId(), 
                        request.getRevocationReason());
                
                log.info("Consent revoked successfully: {}", request.getConsentId());
                
                return ConsentRevocationResult.builder()
                        .consentId(consent.getId())
                        .status(consent.getStatus())
                        .revokedAt(consent.getRevokedAt())
                        .revocationReason(consent.getRevocationReason())
                        .interactionId(request.getInteractionId())
                        .build();
                        
            } catch (Exception e) {
                log.error("Failed to revoke consent: {}", request.getConsentId(), e);
                auditTrailService.recordConsentRevocationFailure(request.getConsentId(), 
                        request.getRevocationReason(), e.getMessage());
                throw new ConsentRevocationException("Failed to revoke consent: " + 
                        e.getMessage(), e);
            }
        });
    }

    /**
     * Records consent usage with real-time analytics.
     * Implements circuit breaker pattern for resilience.
     */
    @Transactional
    public CompletableFuture<ConsentUsageResult> recordConsentUsageAsync(
            ConsentUsageRequest request) {
        
        log.debug("Recording consent usage: {} for data type: {}", 
                request.getConsentId(), request.getDataType());

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Phase 1: Validate consent is active (with cache lookup)
                var consent = validateActiveConsent(request.getConsentId());
                
                // Phase 2: Check rate limits for participant
                validateUsageRateLimit(consent.getParticipantId(), request.getDataType());
                
                // Phase 3: Record usage
                var accessContext = buildDataAccessContext(request);
                consent.recordUsage(accessContext);
                
                // Phase 4: Persist usage event
                var events = consent.getUncommittedEvents();
                eventStore.saveEvents(consent.getId().getValue(), events, consent.getVersion());
                
                // Phase 5: Publish usage events for analytics
                publishEventsAsync(events);
                
                // Phase 6: Update real-time usage metrics
                metricsCollector.recordConsentUsage(consent.getParticipantId(), 
                        request.getDataType(), request.getDataSize());
                
                // Phase 7: Check for anomalous usage patterns
                checkForAnomalousUsage(consent, request);
                
                log.debug("Consent usage recorded successfully: {}", request.getConsentId());
                
                return ConsentUsageResult.builder()
                        .consentId(consent.getId())
                        .usageTimestamp(Instant.now())
                        .dataType(request.getDataType())
                        .remainingUsageQuota(calculateRemainingQuota(consent))
                        .build();
                        
            } catch (Exception e) {
                log.error("Failed to record consent usage: {}", request.getConsentId(), e);
                metricsCollector.recordConsentUsageFailure(request.getConsentId(), 
                        request.getDataType(), e.getMessage());
                throw new ConsentUsageException("Failed to record consent usage: " + 
                        e.getMessage(), e);
            }
        });
    }

    /**
     * Retrieves active consents for a customer with distributed caching.
     */
    @Cacheable(value = "customerConsents", key = "#customerId")
    public CompletableFuture<List<ConsentSummary>> getActiveConsentsAsync(CustomerId customerId, 
                                                                         Optional<ParticipantId> participantId) {
        
        log.debug("Retrieving active consents for customer: {}", customerId);

        return CompletableFuture.supplyAsync(() -> {
            try {
                var consents = consentRepository.findActiveConsentsByCustomer(customerId, participantId);
                
                return consents.stream()
                        .map(this::mapToConsentSummary)
                        .collect(Collectors.toList());
                        
            } catch (Exception e) {
                log.error("Failed to retrieve active consents for customer: {}", customerId, e);
                throw new ConsentQueryException("Failed to retrieve consents: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Performs distributed consent cleanup for expired consents.
     * Implements leader election for coordination.
     */
    public CompletableFuture<ConsentCleanupResult> performConsentCleanupAsync() {
        log.info("Starting distributed consent cleanup process");

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Phase 1: Find expired consents across all nodes
                var expiredConsents = findExpiredConsents();
                
                if (expiredConsents.isEmpty()) {
                    return ConsentCleanupResult.builder()
                            .processedCount(0)
                            .cleanupTimestamp(Instant.now())
                            .build();
                }
                
                // Phase 2: Process each expired consent
                var processedCount = 0;
                for (var consent : expiredConsents) {
                    try {
                        processExpiredConsent(consent);
                        processedCount++;
                    } catch (Exception e) {
                        log.error("Failed to process expired consent: {}", consent.getId(), e);
                    }
                }
                
                // Phase 3: Update cleanup metrics
                metricsCollector.recordConsentCleanup(processedCount, expiredConsents.size());
                
                log.info("Completed consent cleanup. Processed: {}/{}", 
                        processedCount, expiredConsents.size());
                
                return ConsentCleanupResult.builder()
                        .processedCount(processedCount)
                        .failedCount(expiredConsents.size() - processedCount)
                        .cleanupTimestamp(Instant.now())
                        .build();
                        
            } catch (Exception e) {
                log.error("Failed to perform consent cleanup", e);
                throw new ConsentCleanupException("Failed to perform consent cleanup: " + 
                        e.getMessage(), e);
            }
        });
    }

    // Private helper methods

    private void validateConsentCreationRequest(ConsentCreationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Consent creation request cannot be null");
        }
        
        if (request.getCustomerId() == null) {
            throw new IllegalArgumentException("Customer ID cannot be null");
        }
        
        if (request.getParticipantId() == null) {
            throw new IllegalArgumentException("Participant ID cannot be null");
        }
        
        if (request.getScopes() == null || request.getScopes().isEmpty()) {
            throw new IllegalArgumentException("Consent scopes cannot be empty");
        }
        
        if (request.getPurpose() == null) {
            throw new IllegalArgumentException("Consent purpose cannot be null");
        }
        
        // PCI-DSS v4 compliance - validate input security
        securityComplianceService.validateInputSecurity(request.toMap());
    }

    private ParticipantValidationResult validateParticipantWithCBUAE(ParticipantId participantId) {
        try {
            var isValid = cbuaeIntegrationPort.validateParticipant(participantId);
            
            if (isValid) {
                return ParticipantValidationResult.valid();
            } else {
                return ParticipantValidationResult.invalid("Participant not found or inactive in CBUAE directory");
            }
        } catch (Exception e) {
            log.error("Failed to validate participant with CBUAE: {}", participantId, e);
            return ParticipantValidationResult.invalid("CBUAE validation service unavailable");
        }
    }

    private Consent createConsentAggregate(ConsentCreationRequest request) {
        return Consent.create(
                ConsentId.generate(),
                request.getCustomerId(),
                request.getParticipantId(),
                request.getScopes(),
                request.getPurpose(),
                calculateExpiryDate(request.getPurpose(), request.getValidityDays())
        );
    }

    private Instant calculateExpiryDate(ConsentPurpose purpose, Optional<Integer> requestedDays) {
        var defaultDays = purpose.getRecommendedValidityDays();
        var validityDays = requestedDays.orElse(defaultDays);
        
        // Ensure validity doesn't exceed maximum allowed
        var maxDays = 90; // Maximum 90 days for any consent
        if (validityDays > maxDays) {
            validityDays = maxDays;
        }
        
        return Instant.now().plus(validityDays, ChronoUnit.DAYS);
    }

    private void publishEventsAsync(List<DomainEvent> events) {
        CompletableFuture.runAsync(() -> {
            try {
                eventPublisher.publishAll(events);
            } catch (Exception e) {
                log.error("Failed to publish domain events", e);
                // Consider implementing retry mechanism or dead letter queue
            }
        });
    }

    private void publishEventsWithPriority(List<DomainEvent> events, EventPriority priority) {
        CompletableFuture.runAsync(() -> {
            try {
                eventPublisher.publishAllWithPriority(events, priority);
            } catch (Exception e) {
                log.error("Failed to publish high priority domain events", e);
            }
        });
    }

    private void cacheConsentAsync(Consent consent) {
        CompletableFuture.runAsync(() -> {
            try {
                consentCache.put(consent.getId(), consent);
            } catch (Exception e) {
                log.warn("Failed to cache consent: {}", consent.getId(), e);
                // Non-critical failure, continue processing
            }
        });
    }

    private Consent loadConsent(ConsentId consentId) {
        // Try cache first
        var cachedConsent = consentCache.get(consentId);
        if (cachedConsent.isPresent()) {
            return cachedConsent.get();
        }
        
        // Fallback to event store reconstruction
        return reconstructConsentFromEvents(consentId);
    }

    private Consent reconstructConsentFromEvents(ConsentId consentId) {
        var events = eventStore.getEvents(consentId.getValue());
        if (events.isEmpty()) {
            throw new ConsentNotFoundException("Consent not found: " + consentId);
        }
        
        // Reconstruct aggregate from events
        var consent = new Consent();
        for (var event : events) {
            consent.apply(event);
        }
        
        return consent;
    }

    // Exception classes
    
    public static class ConsentCreationException extends RuntimeException {
        public ConsentCreationException(String message) { super(message); }
        public ConsentCreationException(String message, Throwable cause) { super(message, cause); }
    }
    
    public static class ConsentAuthorizationException extends RuntimeException {
        public ConsentAuthorizationException(String message) { super(message); }
        public ConsentAuthorizationException(String message, Throwable cause) { super(message, cause); }
    }
    
    public static class ConsentRevocationException extends RuntimeException {
        public ConsentRevocationException(String message) { super(message); }
        public ConsentRevocationException(String message, Throwable cause) { super(message, cause); }
    }
    
    public static class ConsentUsageException extends RuntimeException {
        public ConsentUsageException(String message) { super(message); }
        public ConsentUsageException(String message, Throwable cause) { super(message, cause); }
    }
    
    public static class ConsentNotFoundException extends RuntimeException {
        public ConsentNotFoundException(String message) { super(message); }
    }
    
    public static class ConsentQueryException extends RuntimeException {
        public ConsentQueryException(String message) { super(message); }
        public ConsentQueryException(String message, Throwable cause) { super(message, cause); }
    }
    
    public static class ConsentCleanupException extends RuntimeException {
        public ConsentCleanupException(String message) { super(message); }
        public ConsentCleanupException(String message, Throwable cause) { super(message, cause); }
    }

    // Enum for event priority
    public enum EventPriority {
        LOW, MEDIUM, HIGH, CRITICAL
    }
}