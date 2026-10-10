package com.enterprise.openfinance.recurringpayments;

import com.enterprise.openfinance.recurringpayments.infrastructure.config.TlsEnforcementInitializer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The startup TLS assertion is wired into this service: on by default in
 * application.yml, off only in the local profile and the test resources, and
 * picked up by every SpringApplication through spring.factories. Needs no
 * database or broker: the assertion runs before any bean is created.
 */
class TlsEnforcementWiringTest {

    private static final String VERIFY_FULL =
            "--spring.datasource.url=jdbc:postgresql://db.internal:5432/db_pay?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";

    @Test
    void enforcementIsOnByDefaultAndOffOnlyInLocalAndTestConfiguration() throws Exception {
        assertThat(yaml("application.yml").getProperty("fintechbankx.tls.enforce")).isEqualTo(true);
        assertThat(yaml("application-local.yml").getProperty("fintechbankx.tls.enforce")).isEqualTo(false);
        assertThat(new PropertiesPropertySourceLoader().load("test", new ClassPathResource("application.properties"))
                .get(0).getProperty("fintechbankx.tls.enforce")).isEqualTo("false");
        for (String profile : new String[] {"application-kafka-msk.yml", "application-kafka-strimzi.yml"}) {
            assertThat(yaml(profile).getProperty("fintechbankx.tls.enforce")).as(profile).isNull();
        }
    }

    @Test
    void bothKafkaProfilesSatisfyTheKafkaAssertion() throws Exception {
        // MSK: SASL_SSL with IAM. Strimzi: SSL, mutual TLS with the KafkaUser certificate.
        assertThat(yaml("application-kafka-msk.yml").getProperty("spring.kafka.security.protocol")).isEqualTo("SASL_SSL");
        assertThat(yaml("application-kafka-strimzi.yml").getProperty("spring.kafka.security.protocol")).isEqualTo("SSL");
        for (String profile : new String[] {"application-kafka-msk.yml", "application-kafka-strimzi.yml"}) {
            serviceConfiguration(profile).run(context -> assertThat(context).as(profile).hasNotFailed());
        }
        // The runtime-neutral default (no profile) is PLAINTEXT and is refused.
        serviceConfiguration(null).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).hasMessageContaining("found PLAINTEXT");
        });
    }

    /**
     * The service's own configuration (application.yml, optionally one Kafka profile
     * overlay on top), a configured Kafka client and enforcement on, run through the
     * initializer only; no broker or database is touched.
     */
    private static ApplicationContextRunner serviceConfiguration(String profileFile) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    MutablePropertySources sources = context.getEnvironment().getPropertySources();
                    PropertySource<?> base = yamlUnchecked("application.yml");
                    sources.addLast(base);
                    if (profileFile != null) {
                        sources.addBefore(base.getName(), yamlUnchecked(profileFile));
                    }
                })
                .withInitializer(new TlsEnforcementInitializer())
                .withBean(KafkaTemplate.class, () -> Mockito.mock(KafkaTemplate.class))
                .withPropertyValues("fintechbankx.tls.enforce=true", VERIFY_FULL.substring(2));
    }

    private static PropertySource<?> yamlUnchecked(String file) {
        try {
            return yaml(file);
        } catch (Exception e) {
            throw new IllegalStateException(file, e);
        }
    }

    @Test
    void aSpringApplicationOfThisServiceRefusesADatasourceWithoutVerifyFull() {
        SpringApplication application = plain(Empty.class);

        assertThat(rootCause(catchThrowable(() -> application.run("--fintechbankx.tls.enforce=true",
                "--spring.datasource.url=jdbc:postgresql://db.internal:5432/db_pay?sslmode=require"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.datasource.url")
                .hasMessageContaining("sslmode=verify-full")
                .hasMessageContaining("found sslmode=require")
                .hasMessageNotContaining("db.internal");
    }

    @Test
    void theWholeServiceRefusesAPlaintextKafkaClientBeforeTouchingTheDatabase() {
        // The real auto-configuration registers the Kafka producer; the assertion runs before
        // Hikari, Flyway or the JWT decoder are created, so an unreachable database is not reached.
        SpringApplication application = plain(RecurringMandatesApplication.class);

        assertThat(rootCause(catchThrowable(() -> application.run("--fintechbankx.tls.enforce=true", VERIFY_FULL,
                "--spring.kafka.security.protocol=PLAINTEXT", "--mandates.outbox.relay.enabled=false"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Kafka producer")
                .hasMessageContaining("spring.kafka.security.protocol")
                .hasMessageContaining("found PLAINTEXT");
    }

    @Test
    void aSpringApplicationOfThisServiceStartsWithVerifyFullWhenEnforced() {
        try (ConfigurableApplicationContext context = plain(Empty.class).run("--fintechbankx.tls.enforce=true", VERIFY_FULL)) {
            assertThat(context.isActive()).isTrue();
        }
    }

    private static SpringApplication plain(Class<?> source) {
        SpringApplication application = new SpringApplication(source);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        return application;
    }

    /** The assertion's own exception, whether or not SpringApplication wrapped it. */
    private static Throwable rootCause(Throwable failure) {
        assertThat(failure).as("the application must not start").isNotNull();
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static PropertySource<?> yaml(String file) throws Exception {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).get(0);
    }

    @Configuration
    static class Empty {
    }
}
