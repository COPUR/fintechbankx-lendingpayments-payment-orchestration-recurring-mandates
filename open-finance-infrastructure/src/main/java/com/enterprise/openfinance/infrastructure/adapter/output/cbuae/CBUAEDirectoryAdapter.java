package com.enterprise.openfinance.infrastructure.adapter.output.cbuae;

import com.enterprise.openfinance.domain.model.participant.Participant;
import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import com.enterprise.openfinance.domain.model.participant.ParticipantCertificate;
import com.enterprise.openfinance.domain.port.output.CBUAEIntegrationPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Adapter for integrating with CBUAE Trust Framework services.
 * Implements the CBUAEIntegrationPort using reactive WebClient for external API calls.
 */
@Slf4j
@Component
public class CBUAEDirectoryAdapter implements CBUAEIntegrationPort {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final int maxRetries;

    public CBUAEDirectoryAdapter(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${open-finance.cbuae.api-base-url}") String baseUrl,
            @Value("${open-finance.cbuae.request-timeout:PT30S}") Duration requestTimeout,
            @Value("${open-finance.cbuae.max-retries:3}") int maxRetries) {
        
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .build();
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
    }

    // Constructor for testing
    CBUAEDirectoryAdapter(WebClient webClient) {
        this.webClient = webClient;
        this.objectMapper = new ObjectMapper();
        this.requestTimeout = Duration.ofSeconds(30);
        this.maxRetries = 3;
    }

    @Override
    public List<Participant> syncParticipantDirectory() {
        log.info("Syncing participant directory from CBUAE");
        
        try {
            String response = webClient
                    .get()
                    .uri("/participants")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(requestTimeout)
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(2)))
                    .block();

            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode participantsNode = rootNode.get("participants");
            
            List<Participant> participants = new java.util.ArrayList<>();
            for (JsonNode participantNode : participantsNode) {
                participants.add(mapToParticipant(participantNode));
            }
            
            log.info("Successfully synced {} participants from CBUAE directory", participants.size());
            return participants;
            
        } catch (WebClientResponseException e) {
            log.error("Failed to sync participant directory: HTTP {}", e.getStatusCode(), e);
            throw new CBUAEIntegrationException(
                    "Failed to sync participant directory: " + e.getMessage(),
                    extractErrorCode(e),
                    e
            );
        } catch (Exception e) {
            log.error("Unexpected error during participant directory sync", e);
            throw new CBUAEIntegrationException(
                    "Failed to sync participant directory: " + e.getMessage(),
                    "SYNC_ERROR",
                    e
            );
        }
    }

    @Override
    public Optional<Participant> getParticipant(ParticipantId participantId) {
        log.debug("Retrieving participant {} from CBUAE directory", participantId);
        
        try {
            String response = webClient
                    .get()
                    .uri("/participants/{id}", participantId.getValue())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(requestTimeout)
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(2)))
                    .block();

            JsonNode participantNode = objectMapper.readTree(response);
            Participant participant = mapToParticipant(participantNode);
            
            log.debug("Successfully retrieved participant {} from CBUAE directory", participantId);
            return Optional.of(participant);
            
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                log.debug("Participant {} not found in CBUAE directory", participantId);
                return Optional.empty();
            }
            
            log.error("Failed to retrieve participant {}: HTTP {}", participantId, e.getStatusCode(), e);
            throw new CBUAEIntegrationException(
                    "Failed to retrieve participant: " + e.getMessage(),
                    extractErrorCode(e),
                    e
            );
        } catch (Exception e) {
            log.error("Unexpected error retrieving participant {}", participantId, e);
            throw new CBUAEIntegrationException(
                    "Failed to retrieve participant: " + e.getMessage(),
                    "RETRIEVAL_ERROR",
                    e
            );
        }
    }

    @Override
    public boolean validateParticipant(ParticipantId participantId) {
        log.debug("Validating participant {} with CBUAE", participantId);
        
        try {
            String response = webClient
                    .get()
                    .uri("/participants/{id}/validate", participantId.getValue())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(requestTimeout)
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(2)))
                    .block();

            JsonNode validationNode = objectMapper.readTree(response);
            boolean isValid = validationNode.get("valid").asBoolean();
            
            log.debug("Participant {} validation result: {}", participantId, isValid);
            return isValid;
            
        } catch (WebClientResponseException e) {
            log.error("Failed to validate participant {}: HTTP {}", participantId, e.getStatusCode(), e);
            throw new CBUAEIntegrationException(
                    "Failed to validate participant: " + e.getMessage(),
                    extractErrorCode(e),
                    e
            );
        } catch (Exception e) {
            log.error("Unexpected error validating participant {}", participantId, e);
            throw new CBUAEIntegrationException(
                    "Failed to validate participant: " + e.getMessage(),
                    "VALIDATION_ERROR",
                    e
            );
        }
    }

    @Override
    public String registerAPIs(String apiSpecification) {
        log.info("Registering APIs with CBUAE central directory");
        
        try {
            String response = webClient
                    .post()
                    .uri("/api-registry")
                    .bodyValue(apiSpecification)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(requestTimeout)
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(2)))
                    .block();

            JsonNode responseNode = objectMapper.readTree(response);
            String registrationId = responseNode.get("registrationId").asText();
            
            log.info("Successfully registered APIs with CBUAE, registration ID: {}", registrationId);
            return registrationId;
            
        } catch (WebClientResponseException e) {
            log.error("Failed to register APIs: HTTP {}", e.getStatusCode(), e);
            throw new CBUAEIntegrationException(
                    "Failed to register APIs: " + e.getMessage(),
                    extractErrorCode(e),
                    e
            );
        } catch (Exception e) {
            log.error("Unexpected error registering APIs", e);
            throw new CBUAEIntegrationException(
                    "Failed to register APIs: " + e.getMessage(),
                    "REGISTRATION_ERROR",
                    e
            );
        }
    }

    @Override
    public void updateAPIRegistration(String registrationId, String updatedSpecification) {
        log.info("Updating API registration {} with CBUAE", registrationId);
        
        try {
            webClient
                    .put()
                    .uri("/api-registry/{id}", registrationId)
                    .bodyValue(updatedSpecification)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(requestTimeout)
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(2)))
                    .block();

            log.info("Successfully updated API registration {}", registrationId);
            
        } catch (WebClientResponseException e) {
            log.error("Failed to update API registration {}: HTTP {}", registrationId, e.getStatusCode(), e);
            throw new CBUAEIntegrationException(
                    "Failed to update API registration: " + e.getMessage(),
                    extractErrorCode(e),
                    e
            );
        } catch (Exception e) {
            log.error("Unexpected error updating API registration {}", registrationId, e);
            throw new CBUAEIntegrationException(
                    "Failed to update API registration: " + e.getMessage(),
                    "UPDATE_ERROR",
                    e
            );
        }
    }

    @Override
    public boolean validateCertificate(ParticipantCertificate certificate) {
        log.debug("Validating certificate {} with CBUAE PKI", certificate.getSerialNumber());
        
        try {
            String response = webClient
                    .post()
                    .uri("/certificates/validate")
                    .bodyValue(createCertificateValidationRequest(certificate))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(requestTimeout)
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(2)))
                    .block();

            JsonNode validationNode = objectMapper.readTree(response);
            boolean isValid = validationNode.get("valid").asBoolean();
            
            log.debug("Certificate {} validation result: {}", certificate.getSerialNumber(), isValid);
            return isValid;
            
        } catch (WebClientResponseException e) {
            log.error("Failed to validate certificate {}: HTTP {}", certificate.getSerialNumber(), e.getStatusCode(), e);
            throw new CBUAEIntegrationException(
                    "Failed to validate certificate: " + e.getMessage(),
                    extractErrorCode(e),
                    e
            );
        } catch (Exception e) {
            log.error("Unexpected error validating certificate {}", certificate.getSerialNumber(), e);
            throw new CBUAEIntegrationException(
                    "Failed to validate certificate: " + e.getMessage(),
                    "CERT_VALIDATION_ERROR",
                    e
            );
        }
    }

    @Override
    public boolean isCertificateRevoked(String serialNumber) {
        log.debug("Checking certificate revocation status for {}", serialNumber);
        
        try {
            String response = webClient
                    .get()
                    .uri("/certificates/{serialNumber}/revocation-status", serialNumber)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(requestTimeout)
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(2)))
                    .block();

            JsonNode statusNode = objectMapper.readTree(response);
            boolean isRevoked = statusNode.get("revoked").asBoolean();
            
            log.debug("Certificate {} revocation status: {}", serialNumber, isRevoked);
            return isRevoked;
            
        } catch (WebClientResponseException e) {
            log.error("Failed to check certificate revocation status {}: HTTP {}", serialNumber, e.getStatusCode(), e);
            throw new CBUAEIntegrationException(
                    "Failed to check certificate revocation: " + e.getMessage(),
                    extractErrorCode(e),
                    e
            );
        } catch (Exception e) {
            log.error("Unexpected error checking certificate revocation {}", serialNumber, e);
            throw new CBUAEIntegrationException(
                    "Failed to check certificate revocation: " + e.getMessage(),
                    "REVOCATION_CHECK_ERROR",
                    e
            );
        }
    }

    @Override
    public String submitSandboxResults(String testReport) {
        log.info("Submitting test results to CBUAE sandbox");
        
        try {
            String response = webClient
                    .post()
                    .uri("/sandbox/test-results")
                    .bodyValue(testReport)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(requestTimeout)
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(2)))
                    .block();

            JsonNode responseNode = objectMapper.readTree(response);
            String submissionId = responseNode.get("submissionId").asText();
            
            log.info("Successfully submitted test results to CBUAE sandbox, submission ID: {}", submissionId);
            return submissionId;
            
        } catch (WebClientResponseException e) {
            log.error("Failed to submit sandbox results: HTTP {}", e.getStatusCode(), e);
            throw new CBUAEIntegrationException(
                    "Failed to submit sandbox results: " + e.getMessage(),
                    extractErrorCode(e),
                    e
            );
        } catch (Exception e) {
            log.error("Unexpected error submitting sandbox results", e);
            throw new CBUAEIntegrationException(
                    "Failed to submit sandbox results: " + e.getMessage(),
                    "SANDBOX_SUBMISSION_ERROR",
                    e
            );
        }
    }

    @Override
    public void reportSecurityIncident(String incidentReport) {
        log.warn("Reporting security incident to CBUAE");
        
        try {
            webClient
                    .post()
                    .uri("/security/incidents")
                    .bodyValue(incidentReport)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(requestTimeout)
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofSeconds(2)))
                    .block();

            log.info("Successfully reported security incident to CBUAE");
            
        } catch (WebClientResponseException e) {
            log.error("Failed to report security incident: HTTP {}", e.getStatusCode(), e);
            throw new CBUAEIntegrationException(
                    "Failed to report security incident: " + e.getMessage(),
                    extractErrorCode(e),
                    e
            );
        } catch (Exception e) {
            log.error("Unexpected error reporting security incident", e);
            throw new CBUAEIntegrationException(
                    "Failed to report security incident: " + e.getMessage(),
                    "INCIDENT_REPORT_ERROR",
                    e
            );
        }
    }

    @Override
    public CBUAEHealthStatus getHealthStatus() {
        try {
            String response = webClient
                    .get()
                    .uri("/health")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10)) // Shorter timeout for health checks
                    .block();

            JsonNode healthNode = objectMapper.readTree(response);
            
            return new CBUAEHealthStatus(
                    true, // Available since we got a response
                    healthNode.get("status").asText(),
                    healthNode.get("responseTime").asLong(),
                    healthNode.get("lastUpdate").asText()
            );
            
        } catch (Exception e) {
            log.warn("CBUAE health check failed", e);
            return new CBUAEHealthStatus(
                    false,
                    "DOWN",
                    -1,
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            );
        }
    }

    // Helper methods

    private Participant mapToParticipant(JsonNode participantNode) {
        try {
            ParticipantId id = ParticipantId.of(participantNode.get("id").asText());
            String legalName = participantNode.get("legalName").asText();
            
            // Map other fields and create Participant
            // This is simplified for brevity - full implementation would map all fields
            
            return Participant.builder()
                    .id(id)
                    .legalName(legalName)
                    // Add other mapped fields
                    .build();
                    
        } catch (Exception e) {
            throw new RuntimeException("Failed to map participant from JSON", e);
        }
    }

    private String createCertificateValidationRequest(ParticipantCertificate certificate) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "serialNumber", certificate.getSerialNumber(),
                    "issuer", certificate.getIssuer(),
                    "subject", certificate.getSubject()
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create certificate validation request", e);
        }
    }

    private String extractErrorCode(WebClientResponseException e) {
        try {
            JsonNode errorNode = objectMapper.readTree(e.getResponseBodyAsString());
            return errorNode.has("error") ? errorNode.get("error").asText() : "UNKNOWN_ERROR";
        } catch (Exception ex) {
            return "UNKNOWN_ERROR";
        }
    }
}