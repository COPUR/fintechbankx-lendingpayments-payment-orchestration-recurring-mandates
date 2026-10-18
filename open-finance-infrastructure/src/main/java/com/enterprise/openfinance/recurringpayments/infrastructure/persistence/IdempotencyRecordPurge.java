package com.enterprise.openfinance.recurringpayments.infrastructure.persistence;

import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.repository.SpringDataVrpIdempotencyRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Deletes expired idempotency records. Expired rows are already ignored on
 * read; this only bounds the table. Safe to run on every replica.
 */
@Component
public class IdempotencyRecordPurge {

    private final SpringDataVrpIdempotencyRepository records;
    private final Clock clock;

    public IdempotencyRecordPurge(SpringDataVrpIdempotencyRepository records, Clock clock) {
        this.records = records;
        this.clock = clock;
    }

    @Scheduled(cron = "${mandates.idempotency.purge-cron:0 45 * * * *}")
    @Transactional
    public int purgeExpired() {
        return records.deleteExpired(clock.instant());
    }
}
