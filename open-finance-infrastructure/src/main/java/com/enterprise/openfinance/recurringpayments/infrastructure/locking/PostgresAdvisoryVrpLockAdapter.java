package com.enterprise.openfinance.recurringpayments.infrastructure.locking;

import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpLockPort;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.repository.SpringDataVrpMandateRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Per-mandate lock shared by every replica: a PostgreSQL transaction-level
 * advisory lock on a hash of the mandate id. It is released when the caller's
 * transaction commits or rolls back, i.e. after the payment, the mandate
 * version and the outbox rows are durable.
 */
@Component
public class PostgresAdvisoryVrpLockAdapter implements VrpLockPort {

    static final String LOCK_NAMESPACE = "vrp-mandate:";

    private final SpringDataVrpMandateRepository mandates;

    public PostgresAdvisoryVrpLockAdapter(SpringDataVrpMandateRepository mandates) {
        this.mandates = mandates;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T withConsentLock(String consentId, Supplier<T> operation) {
        mandates.lockForTransaction(LOCK_NAMESPACE + consentId);
        return operation.get();
    }
}
