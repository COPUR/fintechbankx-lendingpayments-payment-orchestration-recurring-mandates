package com.enterprise.openfinance.recurringpayments.infrastructure.persistence.repository;

import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity.VrpMandateJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface SpringDataVrpMandateRepository extends JpaRepository<VrpMandateJpaEntity, String> {

    /**
     * Writes the next mandate version only if the stored row is still at the
     * version the change was based on. Returns 0 when another writer won.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update VrpMandateJpaEntity m
               set m.status = :status, m.revokedAt = :revokedAt, m.version = :nextVersion, m.updatedAt = :updatedAt
             where m.consentId = :consentId and m.version = :expectedVersion
            """)
    int compareAndSet(@Param("consentId") String consentId,
                      @Param("status") String status,
                      @Param("revokedAt") Instant revokedAt,
                      @Param("nextVersion") long nextVersion,
                      @Param("expectedVersion") long expectedVersion,
                      @Param("updatedAt") Instant updatedAt);

    /**
     * Serialises collections and revocations on one mandate across replicas.
     * Held until the surrounding transaction ends.
     */
    @Query(value = "select pg_advisory_xact_lock(hashtextextended(:lockKey, 0)) is not null", nativeQuery = true)
    boolean lockForTransaction(@Param("lockKey") String lockKey);
}
