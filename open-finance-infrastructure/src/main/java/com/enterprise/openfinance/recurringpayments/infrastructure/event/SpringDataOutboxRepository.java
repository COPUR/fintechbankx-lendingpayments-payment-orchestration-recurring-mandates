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

    /**
     * Pending rows in insertion order. Parked rows are skipped, and so is every
     * later row of a mandate that has a parked row: its events wait until the
     * parked one is replayed or discarded, so they never go out of order.
     */
    @Query(value = """
            select * from mandate_outbox_event o
             where o.published_at is null and o.parked_at is null
               and not exists (select 1 from mandate_outbox_event p
                                where p.aggregate_id = o.aggregate_id
                                  and p.parked_at is not null
                                  and p.created_seq < o.created_seq)
             order by o.created_seq
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
