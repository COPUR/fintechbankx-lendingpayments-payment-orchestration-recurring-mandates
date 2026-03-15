package com.enterprise.openfinance.application.saga;

import com.enterprise.openfinance.application.saga.model.SagaExecution;
import com.enterprise.openfinance.application.saga.model.SagaId;
import com.enterprise.openfinance.domain.event.*;
import com.enterprise.openfinance.domain.model.consent.ConsentId;
import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import com.enterprise.shared.domain.CustomerId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Saga orchestrator for consent authorization workflow.
 * Handles the complex multi-step process of consent creation, validation, and authorization
 * across distributed services with compensation patterns.
 * 
 * Saga Pattern: Orchestrator-based (centralized coordination)
 * Compensation: Automatic rollback on failure
 * Timeout: 5 minutes for complete workflow
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsentAuthorizationSaga {

    private final SagaOrchestrator sagaOrchestrator;
    private final ConsentValidationService consentValidationService;
    private final ParticipantVerificationService participantVerificationService;
    private final CBUAEIntegrationService cbuaeIntegrationService;
    private final NotificationService notificationService;
    private final AuditService auditService;

    /**
     * Orchestrates the complete consent authorization saga.
     * 
     * Steps:
     * 1. Validate participant credentials and certificates
     * 2. Verify consent request against CBUAE regulations
     * 3. Create consent record in event store
     * 4. Notify customer for authorization
     * 5. Wait for customer authorization (with timeout)
     * 6. Register with CBUAE trust framework
     * 7. Activate consent and notify all parties
     * 
     * Compensations are automatic on any step failure.
     */
    @Transactional
    public CompletableFuture<ConsentAuthorizationResult> orchestrateConsentAuthorization(
            ConsentAuthorizationRequest request) {
        
        var sagaId = SagaId.generate();
        log.info("🎯 Starting consent authorization saga: {} for consent: {}", 
            sagaId, request.getConsentId());

        return sagaOrchestrator.startSaga(sagaId, request)
            .thenCompose(saga -> executeStep1_ValidateParticipant(saga, request))
            .thenCompose(saga -> executeStep2_VerifyConsentRequest(saga, request))
            .thenCompose(saga -> executeStep3_CreateConsentRecord(saga, request))
            .thenCompose(saga -> executeStep4_NotifyCustomer(saga, request))
            .thenCompose(saga -> executeStep5_WaitForAuthorization(saga, request))
            .thenCompose(saga -> executeStep6_RegisterWithCBUAE(saga, request))
            .thenCompose(saga -> executeStep7_ActivateConsent(saga, request))
            .thenApply(saga -> buildSuccessResult(saga, request))
            .exceptionally(throwable -> {
                log.error("❌ Consent authorization saga failed: {}", sagaId, throwable);
                return handleSagaFailure(sagaId, request, throwable);
            });
    }

    private CompletableFuture<SagaExecution> executeStep1_ValidateParticipant(
            SagaExecution saga, ConsentAuthorizationRequest request) {
        
        return sagaOrchestrator.executeStep(saga, "VALIDATE_PARTICIPANT", () ->
            participantVerificationService.validateParticipant(
                request.getParticipantId(),
                request.getCertificates(),
                request.getRequestSignature()
            )
        ).thenCompose(result -> {
            if (result.isSuccess()) {
                log.debug("✅ Step 1 completed: Participant validated for {}", request.getParticipantId());
                return CompletableFuture.completedFuture(saga.markStepCompleted("VALIDATE_PARTICIPANT"));
            } else {
                var error = new SagaStepFailedException(
                    "Participant validation failed: " + result.getErrorMessage(),
                    "VALIDATE_PARTICIPANT",
                    result.getErrorCode()
                );
                return CompletableFuture.failedFuture(error);
            }
        }).exceptionally(throwable -> {
            // Register compensation for participant validation
            saga.addCompensation("VALIDATE_PARTICIPANT", () ->
                participantVerificationService.revokeValidation(request.getParticipantId())
            );
            throw new SagaStepFailedException("Participant validation step failed", 
                "VALIDATE_PARTICIPANT", throwable);
        });
    }

    private CompletableFuture<SagaExecution> executeStep2_VerifyConsentRequest(
            SagaExecution saga, ConsentAuthorizationRequest request) {
        
        return sagaOrchestrator.executeStep(saga, "VERIFY_CONSENT_REQUEST", () ->
            consentValidationService.verifyConsentRequest(
                request.getCustomerId(),
                request.getParticipantId(),
                request.getScopes(),
                request.getPurpose(),
                request.getExpirationDate()
            )
        ).thenCompose(result -> {
            if (result.isValid()) {
                log.debug("✅ Step 2 completed: Consent request verified for {}", request.getConsentId());
                return CompletableFuture.completedFuture(saga.markStepCompleted("VERIFY_CONSENT_REQUEST"));
            } else {
                var violations = result.getViolations();
                var error = new SagaStepFailedException(
                    "Consent request validation failed: " + violations,
                    "VERIFY_CONSENT_REQUEST",
                    "VALIDATION_FAILED"
                );
                return CompletableFuture.failedFuture(error);
            }
        });
    }

    private CompletableFuture<SagaExecution> executeStep3_CreateConsentRecord(
            SagaExecution saga, ConsentAuthorizationRequest request) {
        
        return sagaOrchestrator.executeStep(saga, "CREATE_CONSENT_RECORD", () ->
            consentValidationService.createPendingConsent(
                request.getConsentId(),
                request.getCustomerId(),
                request.getParticipantId(),
                request.getScopes(),
                request.getPurpose(),
                request.getExpirationDate()
            )
        ).thenCompose(consent -> {
            log.debug("✅ Step 3 completed: Consent record created for {}", request.getConsentId());
            
            // Register compensation to delete consent if later steps fail
            saga.addCompensation("CREATE_CONSENT_RECORD", () ->
                consentValidationService.deleteConsent(request.getConsentId())
            );
            
            return CompletableFuture.completedFuture(saga.markStepCompleted("CREATE_CONSENT_RECORD"));
        });
    }

    private CompletableFuture<SagaExecution> executeStep4_NotifyCustomer(
            SagaExecution saga, ConsentAuthorizationRequest request) {
        
        return sagaOrchestrator.executeStep(saga, "NOTIFY_CUSTOMER", () ->
            notificationService.sendConsentAuthorizationRequest(
                request.getCustomerId(),
                request.getConsentId(),
                request.getParticipantId(),
                request.getScopes(),
                request.getAuthorizationUrl(),
                Duration.ofMinutes(5) // 5 minute timeout
            )
        ).thenCompose(notification -> {
            log.debug("✅ Step 4 completed: Customer notified for consent {}", request.getConsentId());
            
            // Register compensation to cancel notification
            saga.addCompensation("NOTIFY_CUSTOMER", () ->
                notificationService.cancelConsentNotification(
                    request.getCustomerId(), 
                    request.getConsentId()
                )
            );
            
            return CompletableFuture.completedFuture(saga.markStepCompleted("NOTIFY_CUSTOMER"));
        });
    }

    private CompletableFuture<SagaExecution> executeStep5_WaitForAuthorization(
            SagaExecution saga, ConsentAuthorizationRequest request) {
        
        log.debug("⏳ Step 5: Waiting for customer authorization for consent {}", request.getConsentId());
        
        return sagaOrchestrator.executeAsyncStep(saga, "WAIT_FOR_AUTHORIZATION", 
            Duration.ofMinutes(5), // 5-minute timeout
            () -> waitForCustomerAuthorization(request.getConsentId())
        ).thenCompose(authResult -> {
            if (authResult.isAuthorized()) {
                log.debug("✅ Step 5 completed: Customer authorized consent {}", request.getConsentId());
                return CompletableFuture.completedFuture(saga.markStepCompleted("WAIT_FOR_AUTHORIZATION"));
            } else if (authResult.isTimeout()) {
                var error = new SagaStepTimeoutException(
                    "Customer authorization timeout for consent: " + request.getConsentId(),
                    "WAIT_FOR_AUTHORIZATION",
                    Duration.ofMinutes(5)
                );
                return CompletableFuture.failedFuture(error);
            } else {
                var error = new SagaStepFailedException(
                    "Customer denied authorization for consent: " + request.getConsentId(),
                    "WAIT_FOR_AUTHORIZATION",
                    "CUSTOMER_DENIED"
                );
                return CompletableFuture.failedFuture(error);
            }
        });
    }

    private CompletableFuture<SagaExecution> executeStep6_RegisterWithCBUAE(
            SagaExecution saga, ConsentAuthorizationRequest request) {
        
        return sagaOrchestrator.executeStep(saga, "REGISTER_WITH_CBUAE", () ->
            cbuaeIntegrationService.registerAuthorizedConsent(
                request.getConsentId(),
                request.getParticipantId(),
                request.getCustomerId(),
                request.getScopes(),
                Instant.now() // authorized timestamp
            )
        ).thenCompose(registrationResult -> {
            if (registrationResult.isSuccess()) {
                log.debug("✅ Step 6 completed: Consent registered with CBUAE {}", request.getConsentId());
                
                // Register compensation to deregister from CBUAE
                saga.addCompensation("REGISTER_WITH_CBUAE", () ->
                    cbuaeIntegrationService.deregisterConsent(request.getConsentId())
                );
                
                return CompletableFuture.completedFuture(saga.markStepCompleted("REGISTER_WITH_CBUAE"));
            } else {
                var error = new SagaStepFailedException(
                    "CBUAE registration failed: " + registrationResult.getErrorMessage(),
                    "REGISTER_WITH_CBUAE",
                    registrationResult.getErrorCode()
                );
                return CompletableFuture.failedFuture(error);
            }
        });
    }

    private CompletableFuture<SagaExecution> executeStep7_ActivateConsent(
            SagaExecution saga, ConsentAuthorizationRequest request) {
        
        return sagaOrchestrator.executeStep(saga, "ACTIVATE_CONSENT", () ->
            consentValidationService.activateConsent(
                request.getConsentId(),
                Instant.now(),
                request.getExpirationDate()
            )
        ).thenCompose(activation -> {
            log.debug("✅ Step 7 completed: Consent activated {}", request.getConsentId());
            
            // Send success notifications
            return CompletableFuture.allOf(
                notificationService.notifyConsentActivated(
                    request.getCustomerId(),
                    request.getConsentId(),
                    request.getParticipantId()
                ),
                notificationService.notifyParticipantConsentReady(
                    request.getParticipantId(),
                    request.getConsentId(),
                    request.getScopes()
                ),
                auditService.recordConsentAuthorized(
                    request.getConsentId(),
                    request.getCustomerId(),
                    request.getParticipantId(),
                    saga.getSagaId()
                )
            ).thenApply(v -> saga.markStepCompleted("ACTIVATE_CONSENT"));
        });
    }

    private CompletableFuture<CustomerAuthorizationResult> waitForCustomerAuthorization(ConsentId consentId) {
        // This would typically wait for an async event or poll a status
        // Implementation would depend on your customer authorization mechanism
        
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Simulate waiting for customer authorization
                // In real implementation, this would:
                // 1. Wait for authorization callback/webhook
                // 2. Poll authorization status with exponential backoff
                // 3. Listen to domain events for authorization
                
                Thread.sleep(2000); // Simulate processing time
                
                // For demo purposes, assume authorization succeeds
                return CustomerAuthorizationResult.authorized(consentId, Instant.now());
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CustomerAuthorizationResult.timeout(consentId);
            }
        });
    }

    private ConsentAuthorizationResult buildSuccessResult(SagaExecution saga, ConsentAuthorizationRequest request) {
        log.info("🎉 Consent authorization saga completed successfully: {} for consent: {}", 
            saga.getSagaId(), request.getConsentId());

        return ConsentAuthorizationResult.builder()
            .sagaId(saga.getSagaId())
            .consentId(request.getConsentId())
            .customerId(request.getCustomerId())
            .participantId(request.getParticipantId())
            .status(ConsentAuthorizationStatus.AUTHORIZED)
            .authorizedAt(Instant.now())
            .completedSteps(saga.getCompletedSteps())
            .executionTimeMs(saga.getExecutionTime().toMillis())
            .build();
    }

    private ConsentAuthorizationResult handleSagaFailure(SagaId sagaId, 
                                                        ConsentAuthorizationRequest request, 
                                                        Throwable throwable) {
        log.error("💥 Executing compensations for failed saga: {}", sagaId);
        
        // Execute compensations in reverse order
        sagaOrchestrator.executeCompensations(sagaId)
            .whenComplete((result, compensationError) -> {
                if (compensationError != null) {
                    log.error("❌ Compensation failed for saga: {}", sagaId, compensationError);
                    auditService.recordSagaCompensationFailed(sagaId, request.getConsentId(), compensationError);
                } else {
                    log.info("✅ Compensations completed successfully for saga: {}", sagaId);
                    auditService.recordSagaCompensationCompleted(sagaId, request.getConsentId());
                }
            });

        var failureReason = extractFailureReason(throwable);
        var status = determineFailureStatus(throwable);

        return ConsentAuthorizationResult.builder()
            .sagaId(sagaId)
            .consentId(request.getConsentId())
            .customerId(request.getCustomerId())
            .participantId(request.getParticipantId())
            .status(status)
            .failureReason(failureReason)
            .failedAt(Instant.now())
            .build();
    }

    private String extractFailureReason(Throwable throwable) {
        if (throwable instanceof SagaStepTimeoutException) {
            return "Customer authorization timeout: " + throwable.getMessage();
        } else if (throwable instanceof SagaStepFailedException) {
            var stepException = (SagaStepFailedException) throwable;
            return "Step failed [" + stepException.getStepName() + "]: " + stepException.getMessage();
        } else {
            return "Unexpected error: " + throwable.getMessage();
        }
    }

    private ConsentAuthorizationStatus determineFailureStatus(Throwable throwable) {
        if (throwable instanceof SagaStepTimeoutException) {
            return ConsentAuthorizationStatus.TIMEOUT;
        } else if (throwable instanceof SagaStepFailedException) {
            var stepException = (SagaStepFailedException) throwable;
            if ("CUSTOMER_DENIED".equals(stepException.getErrorCode())) {
                return ConsentAuthorizationStatus.DENIED;
            } else if ("VALIDATION_FAILED".equals(stepException.getErrorCode())) {
                return ConsentAuthorizationStatus.INVALID;
            } else {
                return ConsentAuthorizationStatus.FAILED;
            }
        } else {
            return ConsentAuthorizationStatus.FAILED;
        }
    }
}