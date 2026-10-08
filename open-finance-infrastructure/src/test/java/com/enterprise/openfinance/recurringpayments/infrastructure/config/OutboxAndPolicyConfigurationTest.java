package com.enterprise.openfinance.recurringpayments.infrastructure.config;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpSettings;
import com.enterprise.openfinance.recurringpayments.infrastructure.event.OutboxRelay;
import com.enterprise.openfinance.recurringpayments.infrastructure.event.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxAndPolicyConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-02-09T10:00:00Z");

    @Test
    void outboxGaugesUseThePlatformMetricNames() {
        SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
        when(outbox.countPending()).thenReturn(4L);
        when(outbox.countParked()).thenReturn(1L);
        when(outbox.oldestPendingOccurredAt()).thenReturn(NOW.minusSeconds(90));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxConfiguration configuration = new OutboxConfiguration();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        configuration.outboxPendingGauge(registry, outbox);
        configuration.outboxParkedGauge(registry, outbox);
        configuration.outboxOldestPendingAgeGauge(registry, outbox, clock);

        assertThat(registry.get("outbox.pending.events").gauge().value()).isEqualTo(4d);
        assertThat(registry.get("outbox.parked.events").gauge().value()).isEqualTo(1d);
        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isEqualTo(90d);

        when(outbox.oldestPendingOccurredAt()).thenReturn(null);
        assertThat(OutboxConfiguration.oldestPendingAgeSeconds(outbox, clock)).isZero();
        assertThat(configuration.mandateEventEnvelopeFactory(JsonMapper.builder().build())).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void relayScheduleRunsRelayAndPurge() {
        OutboxConfiguration.RelayConfiguration relayConfiguration = new OutboxConfiguration.RelayConfiguration();
        SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
        OutboxRelay relay = relayConfiguration.outboxRelay(outbox, mock(KafkaTemplate.class),
                mock(PlatformTransactionManager.class), Clock.systemUTC(), 10, 3, Duration.ofSeconds(1), Duration.ofDays(1));
        assertThat(relay).isNotNull();

        OutboxRelay mockRelay = mock(OutboxRelay.class);
        OutboxConfiguration.RelaySchedule schedule = relayConfiguration.relaySchedule(mockRelay);
        schedule.relay();
        schedule.purge();
        verify(mockRelay).relayOnce();
        verify(mockRelay).purgePublished();
    }

    @Test
    void policyAndCachePropertiesBecomeVrpSettings() {
        RecurringPaymentsPolicyProperties policy = new RecurringPaymentsPolicyProperties();
        policy.setIdempotencyTtl(Duration.ofHours(12));
        RecurringPaymentsCacheProperties cache = new RecurringPaymentsCacheProperties();
        cache.setTtl(Duration.ofSeconds(15));
        cache.setMaxEntries(5);
        RecurringPaymentsConfiguration configuration = new RecurringPaymentsConfiguration();

        VrpSettings settings = configuration.vrpSettings(policy, cache);

        assertThat(settings.idempotencyTtl()).isEqualTo(Duration.ofHours(12));
        assertThat(settings.cacheTtl()).isEqualTo(Duration.ofSeconds(15));
        assertThat(cache.getMaxEntries()).isEqualTo(5);
        assertThat(policy.getIdempotencyTtl()).isEqualTo(Duration.ofHours(12));
        assertThat(configuration.vrpClock().getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
