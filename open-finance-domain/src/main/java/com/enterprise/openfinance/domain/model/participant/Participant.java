package com.enterprise.openfinance.domain.model.participant;

import com.enterprise.openfinance.domain.event.ParticipantDeactivatedEvent;
import com.enterprise.openfinance.domain.event.ParticipantOnboardedEvent;
import com.bank.shared.kernel.domain.Entity;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Participant entity representing an Open Finance ecosystem participant registered with CBUAE.
 * Can be a bank, fintech, or other financial institution authorized to participate in data sharing.
 */
@Getter
@Entity
public class Participant extends Entity<ParticipantId> {

    private final ParticipantId id;
    private final String legalName;
    private final ParticipantRole role;
    private final LocalDateTime createdAt;
    
    private CBUAERegistration registration;
    private final Set<ParticipantCertificate> certificates;

    @Builder
    public Participant(
            ParticipantId id,
            String legalName,
            ParticipantRole role,
            CBUAERegistration registration) {
        
        validateRequiredFields(id, legalName, role, registration);
        
        this.id = id;
        this.legalName = legalName;
        this.role = role;
        this.registration = registration;
        this.createdAt = LocalDateTime.now();
        this.certificates = new HashSet<>();
        
        addDomainEvent(new ParticipantOnboardedEvent(
                this.id,
                this.legalName,
                this.role,
                this.createdAt
        ));
    }

    /**
     * Adds a certificate to the participant.
     * Used for mTLS authentication with CBUAE and other participants.
     */
    public void addCertificate(ParticipantCertificate certificate) {
        if (certificate == null) {
            throw new IllegalArgumentException("Certificate cannot be null");
        }
        this.certificates.add(certificate);
    }

    /**
     * Removes a certificate from the participant.
     */
    public void removeCertificate(ParticipantCertificate certificate) {
        if (certificate == null) {
            throw new IllegalArgumentException("Certificate cannot be null");
        }
        this.certificates.remove(certificate);
    }

    /**
     * Checks if the participant has a specific certificate.
     */
    public boolean hasCertificate(ParticipantCertificate certificate) {
        return certificates.contains(certificate);
    }

    /**
     * Gets all active certificates for the participant.
     */
    public Set<ParticipantCertificate> getActiveCertificates() {
        return certificates.stream()
                .filter(ParticipantCertificate::isActive)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Checks if the participant has any active certificates.
     */
    public boolean hasActiveCertificates() {
        return !getActiveCertificates().isEmpty();
    }

    /**
     * Deactivates the participant for regulatory or business reasons.
     */
    public void deactivate(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Deactivation reason cannot be null or empty");
        }
        
        this.registration = this.registration.suspend(reason);
        
        addDomainEvent(new ParticipantDeactivatedEvent(
                this.id,
                this.legalName,
                reason,
                LocalDateTime.now()
        ));
    }

    /**
     * Reactivates the participant after deactivation.
     */
    public void reactivate() {
        if (!registration.getStatus().canBeReactivated()) {
            throw new IllegalStateException("Participant cannot be reactivated from current status: " + 
                    registration.getStatus());
        }
        
        this.registration = this.registration.activate();
    }

    /**
     * Updates the CBUAE registration information.
     */
    public void updateRegistration(CBUAERegistration newRegistration) {
        if (newRegistration == null) {
            throw new IllegalArgumentException("Registration cannot be null");
        }
        this.registration = newRegistration;
    }

    /**
     * Checks if the participant is currently active and can participate in Open Finance.
     */
    public boolean isActive() {
        return registration.getStatus() == CBUAERegistrationStatus.ACTIVE &&
               hasActiveCertificates();
    }

    /**
     * Checks if the participant can share data (is a data holder).
     */
    public boolean canShareData() {
        return role.canShareData() && isActive();
    }

    /**
     * Checks if the participant can receive data (is a data recipient).
     */
    public boolean canReceiveData() {
        return role.canReceiveData() && isActive();
    }

    /**
     * Checks if the participant can initiate payments.
     */
    public boolean canInitiatePayments() {
        return role.canInitiatePayments() && isActive();
    }

    /**
     * Gets the participant's regulatory license information.
     */
    public String getLicenseNumber() {
        return registration.getLicenseNumber();
    }

    /**
     * Checks if the participant's registration is about to expire.
     */
    public boolean isRegistrationExpiringSoon(int days) {
        return registration.isExpiringSoon(days);
    }

    private void validateRequiredFields(
            ParticipantId id,
            String legalName,
            ParticipantRole role,
            CBUAERegistration registration) {
        
        if (id == null) {
            throw new IllegalArgumentException("Participant ID cannot be null");
        }
        if (legalName == null || legalName.trim().isEmpty()) {
            throw new IllegalArgumentException("Legal name cannot be null or empty");
        }
        if (role == null) {
            throw new IllegalArgumentException("Participant role cannot be null");
        }
        if (registration == null) {
            throw new IllegalArgumentException("CBUAE registration cannot be null");
        }
    }

    @Override
    public ParticipantId getId() {
        return id;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        Participant participant = (Participant) obj;
        return id.equals(participant.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return String.format("Participant{id=%s, legalName='%s', role=%s, status=%s}",
                id, legalName, role, registration.getStatus());
    }
}