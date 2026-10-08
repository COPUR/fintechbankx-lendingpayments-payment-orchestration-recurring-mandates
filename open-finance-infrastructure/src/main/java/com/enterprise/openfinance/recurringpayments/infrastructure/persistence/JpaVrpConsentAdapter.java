package com.enterprise.openfinance.recurringpayments.infrastructure.persistence;

import com.enterprise.openfinance.recurringpayments.domain.exception.MandateVersionConflictException;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpConsentPort;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.mapper.VrpPersistenceMapper;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.repository.SpringDataVrpMandateRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/**
 * PostgreSQL store for mandates. Reads return detached copies, so a re-read
 * inside the mandate lock always sees the latest committed row. Updates are a
 * compare-and-set on version; only status and revokedAt ever change.
 */
@Repository
public class JpaVrpConsentAdapter implements VrpConsentPort {

    private final SpringDataVrpMandateRepository mandates;
    private final EntityManager entityManager;
    private final Clock clock;

    public JpaVrpConsentAdapter(SpringDataVrpMandateRepository mandates, EntityManager entityManager, Clock clock) {
        this.mandates = mandates;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public VrpConsent save(VrpConsent consent) {
        if (consent.version() == 0) {
            entityManager.persist(VrpPersistenceMapper.toEntity(consent, clock.instant()));
            entityManager.flush();
            return consent;
        }
        int updated = mandates.compareAndSet(consent.consentId(), consent.status().name(), consent.revokedAt(),
                consent.version(), consent.version() - 1, clock.instant());
        if (updated != 1) {
            throw new MandateVersionConflictException("Mandate " + consent.consentId()
                    + " was changed concurrently; expected version " + (consent.version() - 1));
        }
        return consent;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VrpConsent> findById(String consentId) {
        return mandates.findById(consentId).map(entity -> {
            entityManager.detach(entity);
            return VrpPersistenceMapper.toDomain(entity);
        });
    }
}
