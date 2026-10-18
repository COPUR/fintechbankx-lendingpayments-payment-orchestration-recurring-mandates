package com.enterprise.openfinance.recurringpayments;

import com.enterprise.openfinance.recurringpayments.infrastructure.config.OutboxConfiguration;
import com.enterprise.openfinance.recurringpayments.infrastructure.event.OutboxRelay;
import com.enterprise.openfinance.recurringpayments.infrastructure.event.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Platform convention: every meter carries the common tags service (service id),
 * app (service account) and squad (namespace); the outbox alerts route on squad.
 * Loads the real application.yml, the real OutboxConfiguration and the relay.
 */
class MetricsCommonTagsTest {

    private static final List<String> OUTBOX_METERS = List.of(
            "outbox.pending.events", "outbox.parked.rows", "outbox.oldest.pending.age.seconds",
            "outbox.parked.events", "outbox.send.failures");

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withInitializer(ctx -> {
                applicationYml().forEach(ctx.getEnvironment().getPropertySources()::addLast);
                // As in a booted application: @Value durations such as PT35S.
                ctx.getBeanFactory().setConversionService(
                        org.springframework.boot.convert.ApplicationConversionService.getSharedInstance());
            })
            .withConfiguration(AutoConfigurations.of(MetricsAutoConfiguration.class,
                    CompositeMeterRegistryAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class))
            .withUserConfiguration(OutboxConfiguration.class)
            .withPropertyValues("mandates.outbox.relay.enabled=true", "spring.task.scheduling.enabled=false")
            .withBean(SpringDataOutboxRepository.class, () -> Mockito.mock(SpringDataOutboxRepository.class))
            .withBean(KafkaTemplate.class, () -> Mockito.mock(KafkaTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> Mockito.mock(PlatformTransactionManager.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void everyOutboxMeterCarriesServiceAppAndSquad() {
        context.run(ctx -> {
            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            registerCounters(ctx.getBean(OutboxRelay.class));

            for (String name : OUTBOX_METERS) {
                List<Meter> meters = registry.find(name).meters().stream().toList();
                assertThat(meters).as(name).isNotEmpty();
                meters.forEach(meter -> assertThat(meter.getId().getTags()).as(name).contains(
                        Tag.of("service", "svc-pay-recurring-mandates"),
                        Tag.of("app", "payment-recurring-mandates-service"),
                        Tag.of("squad", "payments")));
            }
        });
    }

    @Test
    void appAndSquadAreOverriddenFromTheEnvironment() {
        context.withPropertyValues("METRICS_TAG_APP=other-service-account", "METRICS_TAG_SQUAD=other-namespace")
                .run(ctx -> {
                    MeterRegistry registry = ctx.getBean(MeterRegistry.class);
                    registerCounters(ctx.getBean(OutboxRelay.class));

                    assertThat(registry.get("outbox.parked.events").counter().getId().getTags()).contains(
                            Tag.of("app", "other-service-account"), Tag.of("squad", "other-namespace"));
                    assertThat(registry.get("outbox.pending.events").gauge().getId().getTags()).contains(
                            Tag.of("app", "other-service-account"), Tag.of("squad", "other-namespace"));
                });
    }

    private static void registerCounters(OutboxRelay relay) {
        // Counters register lazily, on the first park and the first send failure.
        relay.recordParked("RecordTooLargeException");
        relay.recordSendFailure(new IllegalStateException("broker down"));
    }

    private static List<PropertySource<?>> applicationYml() {
        try {
            return new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                    .stream().filter(doc -> doc.getProperty("spring.config.activate.on-profile") == null).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
