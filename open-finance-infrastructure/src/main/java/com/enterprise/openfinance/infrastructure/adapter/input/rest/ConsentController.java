package com.enterprise.openfinance.infrastructure.adapter.input.rest;

import com.enterprise.openfinance.application.saga.ConsentAuthorizationSaga;
import com.enterprise.openfinance.domain.model.consent.ConsentId;
import com.enterprise.openfinance.domain.model.consent.ConsentScope;
import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import com.enterprise.openfinance.domain.port.input.ConsentManagementUseCase;
import com.enterprise.shared.domain.CustomerId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Open Finance Consent Management API Controller.
 * 
 * Implements CBUAE Open Finance regulation C7/2023 consent management standards.
 * Manages the complete consent lifecycle using distributed saga patterns:
 * 
 * 1. Consent Creation & Validation
 * 2. Customer Authorization Flow
 * 3. CBUAE Trust Framework Registration  
 * 4. Consent Activation & Management
 * 5. Consent Revocation & Cleanup
 * 
 * Security: FAPI 2.0 compliant with participant verification and request signatures
 * Orchestration: Uses ConsentAuthorizationSaga for reliable distributed processing
 */
@RestController
@RequestMapping("/open-finance/v1/consents")
@RequiredArgsConstructor
@Slf4j
@Validated
@Tag(name = "Consent Management", description = "Open Finance Consent Management APIs")
@SecurityRequirement(name = "FAPI2-Security")
public class ConsentController {

    private final ConsentManagementUseCase consentManagementUseCase;
    private final ConsentAuthorizationSaga consentAuthorizationSaga;
    private final OpenFinanceSecurityValidator securityValidator;
    private final ParticipantValidator participantValidator;

    /**
     * Create a new consent request.
     * Initiates the distributed saga for consent authorization across all platforms.
     */
    @PostMapping
    @Operation(
        summary = "Create Consent Request",
        description = "Initiate consent request with distributed saga orchestration for cross-platform authorization"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Consent request created successfully",
            content = @Content(schema = @Schema(implementation = ConsentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Invalid consent request"),
        @ApiResponse(responseCode = "401", description = "Invalid participant credentials"),
        @ApiResponse(responseCode = "403", description = "Participant not authorized"),
        @ApiResponse(responseCode = "422", description = "Consent validation failed")
    })
    @PreAuthorize("hasRole('PARTICIPANT')")
    public CompletableFuture<ResponseEntity<ConsentResponse>> createConsent(
            @Parameter(description = "Requesting participant ID", required = true)
            @RequestHeader("X-Participant-Id") 
            @NotBlank @Pattern(regexp = "^BANK-[A-Z0-9]{4,8}$") String participantId,
            
            @Parameter(description = "DPoP proof token for FAPI 2.0", required = true)
            @RequestHeader("DPoP") String dpopProof,
            
            @Parameter(description = "Request signature for non-repudiation", required = true)
            @RequestHeader("X-Request-Signature") String requestSignature,
            
            @Parameter(description = "Participant certificates for mTLS", required = true)
            @RequestHeader("X-Participant-Certificates") String certificates,
            
            @RequestBody @Valid ConsentCreationRequest request) {

        log.info("🎯 Creating consent request - Participant: {}, Customer: {}, Scopes: {}", 
            participantId, request.getCustomerId(), request.getScopes());

        return securityValidator.validateFAPI2Request(dpopProof, requestSignature)
            .thenCompose(securityValidation -> {
                if (!securityValidation.isValid()) {
                    return CompletableFuture.completedFuture(
                        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(ConsentResponse.error("Invalid security validation"))
                    );
                }

                return participantValidator.validateParticipant(
                    ParticipantId.of(participantId), certificates, requestSignature
                );
            })
            .thenCompose(participantValidation -> {
                if (!participantValidation.isValid()) {
                    return CompletableFuture.completedFuture(
                        ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(ConsentResponse.error("Invalid participant credentials"))
                    );
                }

                // Create consent authorization request for saga
                var consentAuthRequest = ConsentAuthorizationRequest.builder()
                    .consentId(ConsentId.generate())
                    .customerId(CustomerId.of(request.getCustomerId()))
                    .participantId(ParticipantId.of(participantId))
                    .scopes(request.getScopes())
                    .purpose(request.getPurpose())
                    .expirationDate(request.getExpirationDate())
                    .authorizationUrl(request.getRedirectUri())
                    .certificates(certificates)
                    .requestSignature(requestSignature)
                    .build();

                // Orchestrate consent authorization using saga pattern
                return consentAuthorizationSaga.orchestrateConsentAuthorization(consentAuthRequest);
            })
            .thenApply(sagaResult -> {
                if (sagaResult.getStatus() == ConsentAuthorizationStatus.AUTHORIZED) {
                    log.info("✅ Consent authorized successfully: {}", sagaResult.getConsentId());
                    
                    var consentResponse = ConsentResponse.builder()
                        .data(ConsentData.builder()
                            .consentId(sagaResult.getConsentId().getValue())
                            .customerId(sagaResult.getCustomerId().getValue())
                            .participantId(sagaResult.getParticipantId().getValue())
                            .status(ConsentStatus.AUTHORIZED)
                            .authorizedAt(sagaResult.getAuthorizedAt())
                            .scopes(request.getScopes())
                            .expirationDate(request.getExpirationDate())
                            .purpose(request.getPurpose())
                            .authorizationUrl(generateAuthorizationUrl(sagaResult.getConsentId()))
                            .build())
                        .meta(ResponseMeta.builder()
                            .sagaId(sagaResult.getSagaId().toString())
                            .executionTimeMs(sagaResult.getExecutionTimeMs())
                            .completedSteps(sagaResult.getCompletedSteps())
                            .build())
                        .links(ResponseLinks.builder()
                            .self("/open-finance/v1/consents/" + sagaResult.getConsentId().getValue())
                            .authorize(generateAuthorizationUrl(sagaResult.getConsentId()))
                            .build())
                        .build();

                    return ResponseEntity.status(HttpStatus.CREATED)
                        .location(URI.create("/open-finance/v1/consents/" + sagaResult.getConsentId().getValue()))
                        .body(consentResponse);
                    
                } else {
                    log.warn("❌ Consent authorization failed: {} - {}", 
                        sagaResult.getConsentId(), sagaResult.getFailureReason());
                    
                    var errorResponse = ConsentResponse.builder()
                        .error(ConsentError.builder()
                            .code(mapStatusToErrorCode(sagaResult.getStatus()))
                            .message(sagaResult.getFailureReason())
                            .timestamp(Instant.now())
                            .build())
                        .meta(ResponseMeta.builder()
                            .sagaId(sagaResult.getSagaId().toString())
                            .build())
                        .build();

                    var statusCode = mapStatusToHttpCode(sagaResult.getStatus());
                    return ResponseEntity.status(statusCode).body(errorResponse);
                }
            })
            .exceptionally(throwable -> {
                log.error("💥 Consent creation failed", throwable);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ConsentResponse.error("Internal server error"));
            });
    }

    /**
     * Get consent details by ID.
     */
    @GetMapping("/{consentId}")
    @Operation(
        summary = "Get Consent Details",
        description = "Retrieve consent information and current status"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Consent details retrieved"),
        @ApiResponse(responseCode = "404", description = "Consent not found"),
        @ApiResponse(responseCode = "403", description = "Access denied")
    })
    @PreAuthorize("hasRole('PARTICIPANT') or hasRole('CUSTOMER')")
    public CompletableFuture<ResponseEntity<ConsentResponse>> getConsent(
            @PathVariable @NotBlank @Pattern(regexp = "^CONSENT-[A-Z0-9]{8,12}$") String consentId,
            @RequestHeader("X-Participant-Id") @NotBlank String participantId,
            @RequestHeader("DPoP") String dpopProof) {

        log.info("🔍 Getting consent details: {}", consentId);

        return consentManagementUseCase.getConsentDetails(
            ConsentId.of(consentId),
            ParticipantId.of(participantId)
        ).thenApply(consent -> {
            if (consent.isPresent()) {
                return ResponseEntity.ok(ConsentResponse.from(consent.get()));
            } else {
                return ResponseEntity.notFound().<ConsentResponse>build();
            }
        });
    }

    /**
     * Get all consents for a customer.
     */
    @GetMapping
    @Operation(
        summary = "List Customer Consents",
        description = "Retrieve all consents for a specific customer across all participants"
    )
    @PreAuthorize("hasRole('CUSTOMER')")
    public CompletableFuture<ResponseEntity<ConsentsResponse>> getCustomerConsents(
            @Parameter(description = "Customer ID", required = true)
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            
            @Parameter(description = "Filter by consent status")
            @RequestParam(required = false) ConsentStatus status,
            
            @Parameter(description = "Filter by participant")
            @RequestParam(required = false) String participantId,
            
            @Parameter(description = "Page number for pagination")
            @RequestParam(defaultValue = "0") int page,
            
            @Parameter(description = "Page size for pagination")
            @RequestParam(defaultValue = "20") int size) {

        log.info("📋 Listing consents for customer: {}", customerId);

        return consentManagementUseCase.getCustomerConsents(
            CustomerId.of(customerId),
            participantId != null ? ParticipantId.of(participantId) : null,
            status,
            page,
            size
        ).thenApply(consentsPage -> {
            var consents = consentsPage.getContent().stream()
                .map(ConsentResponse::from)
                .toList();

            return ResponseEntity.ok(ConsentsResponse.builder()
                .data(consents)
                .meta(ResponseMeta.builder()
                    .totalPages(consentsPage.getTotalPages())
                    .totalRecords(consentsPage.getTotalElements())
                    .currentPage(page)
                    .pageSize(size)
                    .build())
                .build());
        });
    }

    /**
     * Revoke consent.
     * Initiates cleanup saga to revoke consent across all platforms.
     */
    @DeleteMapping("/{consentId}")
    @Operation(
        summary = "Revoke Consent",
        description = "Revoke consent and cleanup across all integrated platforms"
    )
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Consent revoked successfully"),
        @ApiResponse(responseCode = "404", description = "Consent not found"),
        @ApiResponse(responseCode = "409", description = "Consent already revoked")
    })
    @PreAuthorize("hasRole('CUSTOMER')")
    public CompletableFuture<ResponseEntity<Void>> revokeConsent(
            @PathVariable @NotBlank String consentId,
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody @Valid ConsentRevocationRequest revocationRequest) {

        log.info("🔄 Revoking consent: {} for customer: {}", consentId, customerId);

        return consentManagementUseCase.revokeConsent(
            ConsentId.of(consentId),
            CustomerId.of(customerId),
            revocationRequest.getReason(),
            revocationRequest.getEffectiveDate()
        ).thenApply(revocationResult -> {
            if (revocationResult.isSuccess()) {
                log.info("✅ Consent revoked successfully: {}", consentId);
                return ResponseEntity.noContent().<Void>build();
            } else {
                log.warn("❌ Consent revocation failed: {}", revocationResult.getErrorMessage());
                return ResponseEntity.status(HttpStatus.CONFLICT).<Void>build();
            }
        });
    }

    /**
     * Update consent permissions.
     */
    @PatchMapping("/{consentId}")
    @Operation(
        summary = "Update Consent",
        description = "Update consent permissions and expiration"
    )
    @PreAuthorize("hasRole('CUSTOMER')")
    public CompletableFuture<ResponseEntity<ConsentResponse>> updateConsent(
            @PathVariable @NotBlank String consentId,
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            @RequestHeader("DPoP") String dpopProof,
            @RequestBody @Valid ConsentUpdateRequest updateRequest) {

        log.info("✏️ Updating consent: {} for customer: {}", consentId, customerId);

        return consentManagementUseCase.updateConsent(
            ConsentId.of(consentId),
            CustomerId.of(customerId),
            updateRequest.getScopes(),
            updateRequest.getExpirationDate(),
            updateRequest.getPurpose()
        ).thenApply(updatedConsent -> {
            if (updatedConsent.isPresent()) {
                return ResponseEntity.ok(ConsentResponse.from(updatedConsent.get()));
            } else {
                return ResponseEntity.notFound().<ConsentResponse>build();
            }
        });
    }

    /**
     * Get consent usage statistics.
     */
    @GetMapping("/{consentId}/usage")
    @Operation(
        summary = "Get Consent Usage Statistics",
        description = "Retrieve usage analytics for consent across all platforms"
    )
    @PreAuthorize("hasRole('CUSTOMER') or hasRole('PARTICIPANT')")
    public CompletableFuture<ResponseEntity<ConsentUsageResponse>> getConsentUsage(
            @PathVariable @NotBlank String consentId,
            @RequestHeader("X-Customer-Id") @NotBlank String customerId,
            @RequestParam(defaultValue = "30") int days) {

        log.info("📊 Getting consent usage: {} for {} days", consentId, days);

        return consentManagementUseCase.getConsentUsage(
            ConsentId.of(consentId),
            CustomerId.of(customerId),
            days
        ).thenApply(usage -> ResponseEntity.ok(ConsentUsageResponse.from(usage)));
    }

    /**
     * Consent authorization callback.
     * Handles customer authorization responses from external authorization servers.
     */
    @PostMapping("/{consentId}/authorize")
    @Operation(
        summary = "Consent Authorization Callback",
        description = "Handle customer authorization response"
    )
    public CompletableFuture<ResponseEntity<ConsentAuthorizationResponse>> handleAuthorizationCallback(
            @PathVariable @NotBlank String consentId,
            @RequestBody @Valid ConsentAuthorizationCallback callback) {

        log.info("🔓 Handling authorization callback for consent: {}", consentId);

        return consentManagementUseCase.processAuthorizationCallback(
            ConsentId.of(consentId),
            callback.getAuthorizationCode(),
            callback.getCustomerConfirmation(),
            callback.getTimestamp()
        ).thenApply(result -> {
            if (result.isSuccess()) {
                return ResponseEntity.ok(ConsentAuthorizationResponse.builder()
                    .consentId(consentId)
                    .status(ConsentStatus.AUTHORIZED)
                    .authorizedAt(result.getAuthorizedAt())
                    .message("Consent authorized successfully")
                    .build());
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ConsentAuthorizationResponse.builder()
                        .consentId(consentId)
                        .status(ConsentStatus.DENIED)
                        .message(result.getErrorMessage())
                        .build());
            }
        });
    }

    /**
     * Health check for consent management services.
     */
    @GetMapping("/health")
    @Operation(summary = "Consent Management Health Check")
    public ResponseEntity<HealthResponse> getHealth() {
        return ResponseEntity.ok(HealthResponse.builder()
            .status("UP")
            .timestamp(Instant.now())
            .services(Map.of(
                "consent-validation", "UP",
                "saga-orchestrator", "UP",
                "cbuae-integration", "UP",
                "participant-verification", "UP"
            ))
            .build());
    }

    // Helper methods

    private String generateAuthorizationUrl(ConsentId consentId) {
        return "/open-finance/v1/consents/" + consentId.getValue() + "/authorize";
    }

    private String mapStatusToErrorCode(ConsentAuthorizationStatus status) {
        return switch (status) {
            case TIMEOUT -> "AUTHORIZATION_TIMEOUT";
            case DENIED -> "CUSTOMER_DENIED";
            case INVALID -> "INVALID_REQUEST";
            case FAILED -> "PROCESSING_FAILED";
            default -> "UNKNOWN_ERROR";
        };
    }

    private HttpStatus mapStatusToHttpCode(ConsentAuthorizationStatus status) {
        return switch (status) {
            case TIMEOUT -> HttpStatus.REQUEST_TIMEOUT;
            case DENIED -> HttpStatus.FORBIDDEN;
            case INVALID -> HttpStatus.UNPROCESSABLE_ENTITY;
            case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}