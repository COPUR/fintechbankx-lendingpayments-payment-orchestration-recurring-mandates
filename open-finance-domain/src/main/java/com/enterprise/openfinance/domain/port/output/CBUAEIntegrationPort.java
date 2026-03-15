package com.enterprise.openfinance.domain.port.output;

import com.enterprise.openfinance.domain.model.participant.Participant;
import com.enterprise.openfinance.domain.model.participant.ParticipantId;
import com.enterprise.openfinance.domain.model.participant.ParticipantCertificate;

import java.util.List;
import java.util.Optional;

/**
 * Port for integrating with CBUAE Trust Framework services.
 * Defines the contract for external CBUAE API interactions.
 */
public interface CBUAEIntegrationPort {

    /**
     * Synchronizes participant directory from CBUAE.
     * 
     * @return List of all registered participants from CBUAE directory
     * @throws CBUAEIntegrationException if synchronization fails
     */
    List<Participant> syncParticipantDirectory();

    /**
     * Retrieves a specific participant from CBUAE directory.
     * 
     * @param participantId The participant to retrieve
     * @return Optional containing the participant if found
     * @throws CBUAEIntegrationException if retrieval fails
     */
    Optional<Participant> getParticipant(ParticipantId participantId);

    /**
     * Validates a participant's status with CBUAE in real-time.
     * 
     * @param participantId The participant to validate
     * @return true if participant is valid and active in CBUAE directory
     * @throws CBUAEIntegrationException if validation fails
     */
    boolean validateParticipant(ParticipantId participantId);

    /**
     * Registers our institution's APIs with CBUAE central directory.
     * 
     * @param apiSpecification The OpenAPI specification to register
     * @return Registration ID assigned by CBUAE
     * @throws CBUAEIntegrationException if registration fails
     */
    String registerAPIs(String apiSpecification);

    /**
     * Updates API registration with CBUAE.
     * 
     * @param registrationId The existing registration ID
     * @param updatedSpecification The updated API specification
     * @throws CBUAEIntegrationException if update fails
     */
    void updateAPIRegistration(String registrationId, String updatedSpecification);

    /**
     * Validates a participant's certificate with CBUAE PKI.
     * 
     * @param certificate The certificate to validate
     * @return true if certificate is valid and not revoked
     * @throws CBUAEIntegrationException if validation fails
     */
    boolean validateCertificate(ParticipantCertificate certificate);

    /**
     * Checks if a certificate has been revoked by CBUAE.
     * 
     * @param serialNumber The certificate serial number
     * @return true if certificate is revoked
     * @throws CBUAEIntegrationException if check fails
     */
    boolean isCertificateRevoked(String serialNumber);

    /**
     * Submits test results to CBUAE sandbox environment.
     * 
     * @param testReport The test execution report
     * @return Submission ID for tracking
     * @throws CBUAEIntegrationException if submission fails
     */
    String submitSandboxResults(String testReport);

    /**
     * Reports a security incident to CBUAE.
     * 
     * @param incidentReport The incident details
     * @throws CBUAEIntegrationException if reporting fails
     */
    void reportSecurityIncident(String incidentReport);

    /**
     * Gets the current CBUAE service health status.
     * 
     * @return Health status information
     * @throws CBUAEIntegrationException if health check fails
     */
    CBUAEHealthStatus getHealthStatus();

    /**
     * Exception thrown when CBUAE integration operations fail.
     */
    class CBUAEIntegrationException extends RuntimeException {
        
        private final String errorCode;
        
        public CBUAEIntegrationException(String message, String errorCode) {
            super(message);
            this.errorCode = errorCode;
        }
        
        public CBUAEIntegrationException(String message, String errorCode, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }
        
        public String getErrorCode() {
            return errorCode;
        }
    }

    /**
     * CBUAE service health status information.
     */
    record CBUAEHealthStatus(
            boolean isAvailable,
            String status,
            long responseTimeMs,
            String lastUpdateTime
    ) {}
}