package com.enterprise.openfinance.recurringpayments.infrastructure.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataOutboxRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    /**
     * Takes the cluster-wide relay lock for the current transaction. Only one
     * replica relays at a time, which keeps each mandate's events in order.
     */
    @Query(value = "select pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryRelayLock(@Param("key") long key);

    /** Pending rows in insertion order; parked rows are skipped. */
    @Query(value = """
            select * from mandate_outbox_event
             where published_at is null and parked_at is null
             order by created_seq
             limit :batchSize
            """, nativeQuery = true)
    List<OutboxEventJpaEntity> findUnpublishedBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("delete from OutboxEventJpaEntity e where e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);

    @Query("select count(e) from OutboxEventJpaEntity e where e.publishedAt is null and e.parkedAt is null")
    long countPending();

    @Query("select count(e) from OutboxEventJpaEntity e where e.parkedAt is not null")
    long countParked();

    @Query("select min(e.occurredAt) from OutboxEventJpaEntity e where e.publishedAt is null and e.parkedAt is null")
    Instant oldestPendingOccurredAt();
}
