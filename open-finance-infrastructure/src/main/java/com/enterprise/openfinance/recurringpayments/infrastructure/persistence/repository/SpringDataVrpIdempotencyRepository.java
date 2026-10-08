package com.enterprise.openfinance.recurringpayments.infrastructure.persistence.repository;

import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity.VrpIdempotencyJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface SpringDataVrpIdempotencyRepository
        extends JpaRepository<VrpIdempotencyJpaEntity, VrpIdempotencyJpaEntity.Key> {

    /**
     * Claims (tpp_id, idempotency_key) for this payment. An expired record is
     * replaced; an active one is left alone and 0 is returned, which the
     * adapter reports as an idempotency conflict. A concurrent insert of the
     * same key waits on the unique index and then sees the winner's row.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into vrp_idempotency_record
                (tpp_id, idempotency_key, request_hash, payment_id, payment_status, expires_at, created_at)
            values (:tppId, :idempotencyKey, :requestHash, :paymentId, :paymentStatus, :expiresAt, :createdAt)
            on conflict (tpp_id, idempotency_key) do update
               set request_hash = excluded.request_hash,
                   payment_id = excluded.payment_id,
                   payment_status = excluded.payment_status,
                   expires_at = excluded.expires_at,
                   created_at = excluded.created_at
             where vrp_idempotency_record.expires_at <= excluded.created_at
            """, nativeQuery = true)
    int claim(@Param("tppId") String tppId,
              @Param("idempotencyKey") String idempotencyKey,
              @Param("requestHash") String requestHash,
              @Param("paymentId") String paymentId,
              @Param("paymentStatus") String paymentStatus,
              @Param("expiresAt") Instant expiresAt,
              @Param("createdAt") Instant createdAt);

    @Modifying
    @Query("delete from VrpIdempotencyJpaEntity r where r.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
