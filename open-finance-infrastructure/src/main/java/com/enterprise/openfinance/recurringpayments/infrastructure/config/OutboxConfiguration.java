package com.enterprise.openfinance.recurringpayments.infrastructure.config;

import com.enterprise.openfinance.recurringpayments.infrastructure.event.MandateEventEnvelopeFactory;
import com.enterprise.openfinance.recurringpayments.infrastructure.event.OutboxRelay;
import com.enterprise.openfinance.recurringpayments.infrastructure.event.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Configuration
public class OutboxConfiguration {

    @Bean
    MandateEventEnvelopeFactory mandateEventEnvelopeFactory(ObjectMapper objectMapper) {
        return new MandateEventEnvelopeFactory(objectMapper);
    }

    /**
     * Platform metric names: outbox_pending_events, outbox_parked_events (alert
     * on any: events that need an operator) and
     * outbox_oldest_pending_age_seconds (the outage alert: retryable failures
     * stop the relay without parking for up to retryable-park-after, so this
     * age, not the parked count, shows a broker or egress outage).
     */
    @Bean
    Gauge outboxPendingGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder("outbox.pending.events", outbox, SpringDataOutboxRepository::countPending)
                .description("Mandate events written to the outbox but not yet published to Kafka")
                .register(registry);
    }

    @Bean
    Gauge outboxParkedGauge(MeterRegistry registry, SpringDataOutboxRepository outbox) {
        return Gauge.builder("outbox.parked.events", outbox, SpringDataOutboxRepository::countParked)
                .description("Mandate events parked by the relay (permanent failure, or retryable failures for longer than retryable-park-after)")
                .register(registry);
    }

    @Bean
    Gauge outboxOldestPendingAgeGauge(MeterRegistry registry, SpringDataOutboxRepository outbox, Clock clock) {
        return Gauge.builder("outbox.oldest.pending.age.seconds", outbox, repo -> oldestPendingAgeSeconds(repo, clock))
                .description("Age of the oldest unpublished, unparked mandate event")
                .register(registry);
    }

    static double oldestPendingAgeSeconds(SpringDataOutboxRepository outbox, Clock clock) {
        Instant oldest = outbox.oldestPendingOccurredAt();
        return oldest == null ? 0d : Math.max(0d, Duration.between(oldest, clock.instant()).toMillis() / 1000d);
    }

    /**
     * The relay runs in every replica; the advisory lock lets only one of them
     * publish at a time. Off by default (mandates.outbox.relay.enabled) until
     * the evt.pay.mandate topics exist on the platform cluster; events wait in
     * the outbox meanwhile.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "mandates.outbox.relay.enabled", havingValue = "true")
    static class RelayConfiguration {

        @Bean
        OutboxRelay outboxRelay(SpringDataOutboxRepository outbox,
                                KafkaTemplate<String, String> kafka,
                                PlatformTransactionManager transactionManager,
                                Clock clock,
                                @Value("${mandates.outbox.relay.batch-size:100}") int batchSize,
                                @Value("${mandates.outbox.relay.send-timeout:PT35S}") Duration sendTimeout,
                                @Value("${mandates.outbox.retention:P7D}") Duration retention,
                                @Value("${mandates.outbox.relay.retryable-park-after:PT24H}") Duration retryableParkAfter) {
            return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager), clock, batchSize,
                    sendTimeout, retention, retryableParkAfter);
        }

        @Bean
        RelaySchedule relaySchedule(OutboxRelay relay) {
            return new RelaySchedule(relay);
        }
    }

    static class RelaySchedule {
        private final OutboxRelay relay;

        RelaySchedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${mandates.outbox.relay.interval:PT1S}")
        void relay() {
            relay.relayOnce();
        }

        @Scheduled(cron = "${mandates.outbox.purge-cron:0 15 3 * * *}")
        void purge() {
            relay.purgePublished();
        }
    }
}
