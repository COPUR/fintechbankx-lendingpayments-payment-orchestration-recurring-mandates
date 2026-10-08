package com.enterprise.openfinance.recurringpayments.infrastructure.persistence.repository;

import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity.VrpPaymentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;

public interface SpringDataVrpPaymentRepository extends JpaRepository<VrpPaymentJpaEntity, String> {

    @Query("""
            select coalesce(sum(p.amount), 0) from VrpPaymentJpaEntity p
             where p.consentId = :consentId and p.periodKey = :periodKey and p.status = 'ACCEPTED'
            """)
    BigDecimal sumAccepted(@Param("consentId") String consentId, @Param("periodKey") String periodKey);
}
