package com.enterprise.openfinance.recurringpayments.infrastructure.persistence;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.port.out.VrpPaymentPort;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.mapper.VrpPersistenceMapper;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.repository.SpringDataVrpPaymentRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/** PostgreSQL store for VRP payments (insert-only). */
@Repository
public class JpaVrpPaymentAdapter implements VrpPaymentPort {

    private final SpringDataVrpPaymentRepository payments;
    private final EntityManager entityManager;

    public JpaVrpPaymentAdapter(SpringDataVrpPaymentRepository payments, EntityManager entityManager) {
        this.payments = payments;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public VrpPayment save(VrpPayment payment) {
        entityManager.persist(VrpPersistenceMapper.toEntity(payment));
        entityManager.flush();
        return payment;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VrpPayment> findById(String paymentId) {
        return payments.findById(paymentId).map(VrpPersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal sumAcceptedAmountByConsentAndPeriod(String consentId, String periodKey) {
        return payments.sumAccepted(consentId, periodKey);
    }
}
