package com.enterprise.openfinance.application.saga;

import com.enterprise.openfinance.domain.event.*;
import com.enterprise.openfinance.domain.model.consent.ConsentId;
import com.enterprise.openfinance.domain.model.consent.ConsentScope;
import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import com.enterprise.shared.domain.CustomerId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Saga orchestrator for data sharing request workflow.
 * Handles the complex multi-step process of validating consents, 
 * aggregating data from multiple sources, and securely sharing data
 * across the Open Finance ecosystem.
 * 
 * Cross-Platform Data Flow:
 * 1. Enterprise Loan Management → Loan and credit data
 * 2. AmanahFi Platform → Islamic finance and Sharia-compliant data  
 * 3. Masrufi Framework → Expense management and budget data
 * 4. External providers → Third-party financial data
 * 
 * Security: End-to-end encryption, PCI-DSS v4 compliance, audit trail
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSharingRequestSaga {

    private final SagaOrchestrator sagaOrchestrator;
    private final ConsentValidationService consentValidationService;
    private final DataAggregationService dataAggregationService;
    private final DataTransformationService dataTransformationService;
    private final DataEncryptionService dataEncryptionService;
    private final AuditService auditService;
    private final RateLimitingService rateLimitingService;
    private final ComplianceService complianceService;

    /**
     * Orchestrates the complete data sharing request saga.
     * 
     * Steps:
     * 1. Validate active consent and scope permissions
     * 2. Check rate limiting and security constraints
     * 3. Aggregate data from multiple platforms (Loan, AmanahFi, Masrufi)
     * 4. Apply data transformation and masking rules
     * 5. Encrypt data for secure transmission  
     * 6. Deliver data to requesting participant
     * 7. Record audit trail and compliance events
     * 
     * Compensations handle partial failures and ensure data consistency.
     */
    @Transactional
    public CompletableFuture<DataSharingResult> orchestrateDataSharingRequest(
            DataSharingRequest request) {
        
        var sagaId = SagaId.generate();
        log.info("📊 Starting data sharing saga: {} for consent: {} participant: {}", 
            sagaId, request.getConsentId(), request.getParticipantId());

        return sagaOrchestrator.startSaga(sagaId, request)
            .thenCompose(saga -> executeStep1_ValidateConsent(saga, request))
            .thenCompose(saga -> executeStep2_CheckRateLimits(saga, request))
            .thenCompose(saga -> executeStep3_AggregateData(saga, request))
            .thenCompose(saga -> executeStep4_TransformData(saga, request))
            .thenCompose(saga -> executeStep5_EncryptData(saga, request))
            .thenCompose(saga -> executeStep6_DeliverData(saga, request))
            .thenCompose(saga -> executeStep7_RecordAuditTrail(saga, request))
            .thenApply(saga -> buildSuccessResult(saga, request))
            .exceptionally(throwable -> {
                log.error("❌ Data sharing saga failed: {}", sagaId, throwable);
                return handleSagaFailure(sagaId, request, throwable);
            });
    }

    private CompletableFuture<SagaExecution> executeStep1_ValidateConsent(
            SagaExecution saga, DataSharingRequest request) {
        
        return sagaOrchestrator.executeStep(saga, "VALIDATE_CONSENT", () ->
            consentValidationService.validateConsentForDataAccess(
                request.getConsentId(),
                request.getParticipantId(),
                request.getRequestedScopes(),
                request.getCustomerId(),
                Instant.now()
            )
        ).thenCompose(validation -> {
            if (validation.isValid()) {
                log.debug("✅ Step 1 completed: Consent validated for data access {}", request.getConsentId());
                return CompletableFuture.completedFuture(saga.markStepCompleted("VALIDATE_CONSENT"));
            } else {
                var violations = validation.getViolations();
                var error = new SagaStepFailedException(
                    "Consent validation failed: " + violations,
                    "VALIDATE_CONSENT",
                    determineValidationErrorCode(violations)
                );
                return CompletableFuture.failedFuture(error);
            }
        });
    }

    private CompletableFuture<SagaExecution> executeStep2_CheckRateLimits(
            SagaExecution saga, DataSharingRequest request) {
        
        return sagaOrchestrator.executeStep(saga, "CHECK_RATE_LIMITS", () ->
            rateLimitingService.checkRateLimit(
                request.getParticipantId(),
                request.getConsentId(),
                request.getRequestedScopes(),
                request.getDataSize()
            )
        ).thenCompose(rateLimitCheck -> {
            if (rateLimitCheck.isAllowed()) {
                log.debug("✅ Step 2 completed: Rate limits passed for participant {}", request.getParticipantId());
                
                // Register compensation to restore rate limit quota on failure
                saga.addCompensation("CHECK_RATE_LIMITS", () ->
                    rateLimitingService.restoreQuota(
                        request.getParticipantId(),
                        request.getRequestedScopes(),
                        request.getDataSize()
                    )
                );
                
                return CompletableFuture.completedFuture(saga.markStepCompleted("CHECK_RATE_LIMITS"));
            } else {
                var error = new SagaStepFailedException(
                    "Rate limit exceeded: " + rateLimitCheck.getErrorMessage(),
                    "CHECK_RATE_LIMITS",
                    "RATE_LIMIT_EXCEEDED"
                );
                return CompletableFuture.failedFuture(error);
            }
        });
    }

    private CompletableFuture<SagaExecution> executeStep3_AggregateData(
            SagaExecution saga, DataSharingRequest request) {
        
        return sagaOrchestrator.executeAsyncStep(saga, "AGGREGATE_DATA", 
            Duration.ofMinutes(2), // 2-minute timeout for data aggregation
            () -> aggregateDataFromAllPlatforms(request)
        ).thenCompose(aggregatedData -> {
            log.debug("✅ Step 3 completed: Data aggregated from {} sources", 
                aggregatedData.getSourceCount());
            
            // Store aggregated data temporarily for next steps
            saga.addContextData("aggregatedData", aggregatedData);
            
            // Register compensation to clean up aggregated data
            saga.addCompensation("AGGREGATE_DATA", () ->
                dataAggregationService.cleanupAggregatedData(aggregatedData.getAggregationId())
            );
            
            return CompletableFuture.completedFuture(saga.markStepCompleted("AGGREGATE_DATA"));
        });
    }

    private CompletableFuture<SagaExecution> executeStep4_TransformData(
            SagaExecution saga, DataSharingRequest request) {
        
        var aggregatedData = (AggregatedData) saga.getContextData("aggregatedData");
        
        return sagaOrchestrator.executeStep(saga, "TRANSFORM_DATA", () ->
            dataTransformationService.transformForParticipant(
                aggregatedData,
                request.getParticipantId(),
                request.getRequestedScopes(),
                request.getDataFormat(),
                request.getComplianceRequirements()
            )
        ).thenCompose(transformedData -> {
            log.debug("✅ Step 4 completed: Data transformed for participant {}", request.getParticipantId());
            
            // Store transformed data for next steps
            saga.addContextData("transformedData", transformedData);
            
            // Register compensation to clean up transformed data
            saga.addCompensation("TRANSFORM_DATA", () ->
                dataTransformationService.cleanupTransformedData(transformedData.getTransformationId())
            );
            
            return CompletableFuture.completedFuture(saga.markStepCompleted("TRANSFORM_DATA"));
        });
    }

    private CompletableFuture<SagaExecution> executeStep5_EncryptData(
            SagaExecution saga, DataSharingRequest request) {
        
        var transformedData = (TransformedData) saga.getContextData("transformedData");
        
        return sagaOrchestrator.executeStep(saga, "ENCRYPT_DATA", () ->
            dataEncryptionService.encryptForParticipant(
                transformedData,
                request.getParticipantId(),
                request.getEncryptionMethod(),
                request.getParticipantPublicKey()
            )
        ).thenCompose(encryptedData -> {
            log.debug("✅ Step 5 completed: Data encrypted for secure transmission to {}", 
                request.getParticipantId());
            
            // Store encrypted data for delivery
            saga.addContextData("encryptedData", encryptedData);
            
            // Register compensation to securely delete encrypted data
            saga.addCompensation("ENCRYPT_DATA", () ->
                dataEncryptionService.securelyDeleteEncryptedData(encryptedData.getEncryptionId())
            );
            
            return CompletableFuture.completedFuture(saga.markStepCompleted("ENCRYPT_DATA"));
        });
    }

    private CompletableFuture<SagaExecution> executeStep6_DeliverData(
            SagaExecution saga, DataSharingRequest request) {
        
        var encryptedData = (EncryptedData) saga.getContextData("encryptedData");
        
        return sagaOrchestrator.executeAsyncStep(saga, "DELIVER_DATA",
            Duration.ofMinutes(1), // 1-minute timeout for data delivery
            () -> dataAggregationService.deliverDataToParticipant(
                encryptedData,
                request.getParticipantId(),
                request.getDeliveryEndpoint(),
                request.getDeliveryMethod(),
                request.getCallbackUrl()
            )
        ).thenCompose(deliveryResult -> {
            if (deliveryResult.isSuccess()) {
                log.debug("✅ Step 6 completed: Data delivered successfully to participant {}", 
                    request.getParticipantId());
                
                // Store delivery confirmation
                saga.addContextData("deliveryResult", deliveryResult);
                
                return CompletableFuture.completedFuture(saga.markStepCompleted("DELIVER_DATA"));
            } else {
                var error = new SagaStepFailedException(
                    "Data delivery failed: " + deliveryResult.getErrorMessage(),
                    "DELIVER_DATA",
                    deliveryResult.getErrorCode()
                );
                return CompletableFuture.failedFuture(error);
            }
        });
    }

    private CompletableFuture<SagaExecution> executeStep7_RecordAuditTrail(
            SagaExecution saga, DataSharingRequest request) {
        
        var deliveryResult = (DataDeliveryResult) saga.getContextData("deliveryResult");
        var aggregatedData = (AggregatedData) saga.getContextData("aggregatedData");
        
        return sagaOrchestrator.executeStep(saga, "RECORD_AUDIT_TRAIL", () ->
            CompletableFuture.allOf(
                // Record data access event
                auditService.recordDataAccess(
                    request.getConsentId(),
                    request.getCustomerId(),
                    request.getParticipantId(),
                    request.getRequestedScopes(),
                    aggregatedData.getDataSources(),
                    deliveryResult.getDeliveryTimestamp(),
                    saga.getSagaId()
                ),
                
                // Record compliance event
                complianceService.recordComplianceEvent(
                    "DATA_SHARED",
                    request.getConsentId(),
                    request.getParticipantId(),
                    Map.of(
                        "dataSize", aggregatedData.getDataSize(),
                        "encryptionMethod", request.getEncryptionMethod(),
                        "deliveryMethod", request.getDeliveryMethod(),
                        "sagaId", saga.getSagaId().toString()
                    )
                ),
                
                // Update consent usage statistics
                consentValidationService.updateConsentUsage(
                    request.getConsentId(),
                    request.getRequestedScopes(),
                    aggregatedData.getDataSize(),
                    Instant.now()
                )
            )
        ).thenCompose(auditResults -> {
            log.debug("✅ Step 7 completed: Audit trail and compliance events recorded");
            return CompletableFuture.completedFuture(saga.markStepCompleted("RECORD_AUDIT_TRAIL"));
        });
    }

    private CompletableFuture<AggregatedData> aggregateDataFromAllPlatforms(DataSharingRequest request) {
        log.debug("🔄 Aggregating data from multiple platforms for consent: {}", request.getConsentId());

        var aggregationTasks = new ArrayList<CompletableFuture<PlatformData>>();

        // Determine which platforms to query based on requested scopes
        if (requiresLoanData(request.getRequestedScopes())) {
            aggregationTasks.add(
                dataAggregationService.aggregateLoanData(
                    request.getCustomerId(),
                    request.getRequestedScopes()
                )
            );
        }

        if (requiresIslamicFinanceData(request.getRequestedScopes())) {
            aggregationTasks.add(
                dataAggregationService.aggregateAmanahFiData(
                    request.getCustomerId(),
                    request.getRequestedScopes()
                )
            );
        }

        if (requiresExpenseData(request.getRequestedScopes())) {
            aggregationTasks.add(
                dataAggregationService.aggregateMasrufiData(
                    request.getCustomerId(),
                    request.getRequestedScopes()
                )
            );
        }

        if (requiresExternalData(request.getRequestedScopes())) {
            aggregationTasks.add(
                dataAggregationService.aggregateExternalData(
                    request.getCustomerId(),
                    request.getRequestedScopes(),
                    request.getParticipantId()
                )
            );
        }

        return CompletableFuture.allOf(aggregationTasks.toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                var platformDataList = aggregationTasks.stream()
                    .map(CompletableFuture::join)
                    .toList();

                return AggregatedData.builder()
                    .aggregationId(AggregationId.generate())
                    .platformDataList(platformDataList)
                    .sourceCount(platformDataList.size())
                    .dataSize(platformDataList.stream().mapToLong(PlatformData::getDataSize).sum())
                    .dataSources(platformDataList.stream()
                        .map(PlatformData::getSourcePlatform)
                        .toList())
                    .aggregatedAt(Instant.now())
                    .build();
            });
    }

    private DataSharingResult buildSuccessResult(SagaExecution saga, DataSharingRequest request) {
        var deliveryResult = (DataDeliveryResult) saga.getContextData("deliveryResult");
        var aggregatedData = (AggregatedData) saga.getContextData("aggregatedData");

        log.info("🎉 Data sharing saga completed successfully: {} for consent: {}", 
            saga.getSagaId(), request.getConsentId());

        return DataSharingResult.builder()
            .sagaId(saga.getSagaId())
            .requestId(request.getRequestId())
            .consentId(request.getConsentId())
            .customerId(request.getCustomerId())
            .participantId(request.getParticipantId())
            .status(DataSharingStatus.COMPLETED)
            .sharedAt(deliveryResult.getDeliveryTimestamp())
            .dataSources(aggregatedData.getDataSources())
            .dataSize(aggregatedData.getDataSize())
            .encryptionMethod(request.getEncryptionMethod())
            .deliveryMethod(request.getDeliveryMethod())
            .executionTimeMs(saga.getExecutionTime().toMillis())
            .build();
    }

    private DataSharingResult handleSagaFailure(SagaId sagaId, 
                                               DataSharingRequest request, 
                                               Throwable throwable) {
        log.error("💥 Executing compensations for failed data sharing saga: {}", sagaId);
        
        // Execute compensations in reverse order
        sagaOrchestrator.executeCompensations(sagaId)
            .whenComplete((result, compensationError) -> {
                if (compensationError != null) {
                    log.error("❌ Compensation failed for data sharing saga: {}", sagaId, compensationError);
                    auditService.recordSagaCompensationFailed(sagaId, request.getConsentId(), compensationError);
                } else {
                    log.info("✅ Data sharing compensations completed successfully for saga: {}", sagaId);
                    auditService.recordSagaCompensationCompleted(sagaId, request.getConsentId());
                }
            });

        var failureReason = extractFailureReason(throwable);
        var status = determineFailureStatus(throwable);

        return DataSharingResult.builder()
            .sagaId(sagaId)
            .requestId(request.getRequestId())
            .consentId(request.getConsentId())
            .customerId(request.getCustomerId())
            .participantId(request.getParticipantId())
            .status(status)
            .failureReason(failureReason)
            .failedAt(Instant.now())
            .build();
    }

    // Helper methods for scope checking
    private boolean requiresLoanData(Set<ConsentScope> scopes) {
        return scopes.contains(ConsentScope.ACCOUNT_INFORMATION) || 
               scopes.contains(ConsentScope.LOAN_INFORMATION);
    }

    private boolean requiresIslamicFinanceData(Set<ConsentScope> scopes) {
        return scopes.contains(ConsentScope.ISLAMIC_FINANCE) || 
               scopes.contains(ConsentScope.SHARIA_COMPLIANCE);
    }

    private boolean requiresExpenseData(Set<ConsentScope> scopes) {
        return scopes.contains(ConsentScope.TRANSACTION_HISTORY) || 
               scopes.contains(ConsentScope.SPENDING_ANALYSIS);
    }

    private boolean requiresExternalData(Set<ConsentScope> scopes) {
        return scopes.contains(ConsentScope.EXTERNAL_ACCOUNTS) || 
               scopes.contains(ConsentScope.THIRD_PARTY_DATA);
    }

    private String determineValidationErrorCode(Set<String> violations) {
        if (violations.contains("CONSENT_EXPIRED")) {
            return "CONSENT_EXPIRED";
        } else if (violations.contains("SCOPE_NOT_PERMITTED")) {
            return "INSUFFICIENT_SCOPE";
        } else if (violations.contains("CONSENT_REVOKED")) {
            return "CONSENT_REVOKED";
        } else {
            return "VALIDATION_FAILED";
        }
    }

    private String extractFailureReason(Throwable throwable) {
        if (throwable instanceof SagaStepTimeoutException) {
            return "Data aggregation or delivery timeout: " + throwable.getMessage();
        } else if (throwable instanceof SagaStepFailedException) {
            var stepException = (SagaStepFailedException) throwable;
            return "Step failed [" + stepException.getStepName() + "]: " + stepException.getMessage();
        } else {
            return "Unexpected error: " + throwable.getMessage();
        }
    }

    private DataSharingStatus determineFailureStatus(Throwable throwable) {
        if (throwable instanceof SagaStepTimeoutException) {
            return DataSharingStatus.TIMEOUT;
        } else if (throwable instanceof SagaStepFailedException) {
            var stepException = (SagaStepFailedException) throwable;
            return switch (stepException.getErrorCode()) {
                case "CONSENT_EXPIRED", "CONSENT_REVOKED" -> DataSharingStatus.UNAUTHORIZED;
                case "INSUFFICIENT_SCOPE" -> DataSharingStatus.FORBIDDEN;
                case "RATE_LIMIT_EXCEEDED" -> DataSharingStatus.RATE_LIMITED;
                default -> DataSharingStatus.FAILED;
            };
        } else {
            return DataSharingStatus.FAILED;
        }
    }
}