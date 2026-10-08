package com.enterprise.openfinance.recurringpayments.infrastructure.persistence;

import com.enterprise.openfinance.recurringpayments.domain.exception.IdempotencyConflictException;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpIdempotencyRecord;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpIdempotencyPort;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity.VrpIdempotencyJpaEntity;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.mapper.VrpPersistenceMapper;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.repository.SpringDataVrpIdempotencyRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Idempotency records in PostgreSQL, unique per (tpp_id, idempotency_key)
 * across all replicas. Expired records are ignored on read and replaced on write.
 */
@Repository
public class JpaVrpIdempotencyAdapter implements VrpIdempotencyPort {

    private final SpringDataVrpIdempotencyRepository records;
    private final Clock clock;

    public JpaVrpIdempotencyAdapter(SpringDataVrpIdempotencyRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VrpIdempotencyRecord> find(String idempotencyKey, String tppId, Instant now) {
        return records.findById(new VrpIdempotencyJpaEntity.Key(tppId, idempotencyKey))
                .filter(entity -> entity.getExpiresAt().isAfter(now))
                .map(VrpPersistenceMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(VrpIdempotencyRecord record) {
        int claimed = records.claim(record.tppId(), record.idempotencyKey(), record.requestHash(),
                record.paymentId(), record.status().name(), record.expiresAt(), clock.instant());
        if (claimed != 1) {
            throw new IdempotencyConflictException("Idempotency conflict");
        }
    }
}
