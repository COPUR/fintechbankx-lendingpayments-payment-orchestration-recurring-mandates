package com.enterprise.openfinance.recurringpayments.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup TLS assertion (governance round 3, answer 2b): the service refuses to
 * start unless the datasource verifies the server certificate
 * (sslmode=verify-full) and, when a Kafka client is configured, the client uses
 * SASL_SSL. {@code fintechbankx.tls.enforce} is true unless configuration says
 * otherwise; only local and test configuration set it false.
 */
class TlsEnforcementInitializerTest {

    private static final String VERIFY_FULL =
            "spring.datasource.url=jdbc:postgresql://db.internal:5432/db_pay?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String REQUIRE =
            "spring.datasource.url=jdbc:postgresql://db.internal:5432/db_pay?sslmode=require&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new TlsEnforcementInitializer());

    private ApplicationContextRunner withKafkaClient() {
        return runner.withBean(KafkaTemplate.class, () -> Mockito.mock(KafkaTemplate.class));
    }

    @Test
    void refusesADatasourceUrlThatOnlyRequiresTlsWithoutVerifyingTheServer() {
        runner.withPropertyValues(REQUIRE).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("fintechbankx.tls.enforce")
                    .hasMessageContaining("spring.datasource.url")
                    .hasMessageContaining("sslmode=verify-full")
                    .hasMessageContaining("found sslmode=require")
                    // the message names the setting, never the URL (it can carry credentials)
                    .hasMessageNotContaining("db.internal");
        });
    }

    @Test
    void refusesADatasourceUrlWithoutAnySslmode() {
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://db.internal:5432/db_pay").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .hasMessageContaining("spring.datasource.url")
                    .hasMessageContaining("found no sslmode");
        });
    }

    @Test
    void refusesAKafkaClientOnPlaintext() {
        withKafkaClient().withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=PLAINTEXT").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("fintechbankx.tls.enforce")
                    .hasMessageContaining("spring.kafka.security.protocol")
                    .hasMessageContaining("SASL_SSL")
                    .hasMessageContaining("found PLAINTEXT");
        });
    }

    @Test
    void aKafkaClientWithNoProtocolAtAllIsPlaintextAndRefused() {
        withKafkaClient().withPropertyValues(VERIFY_FULL).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).hasMessageContaining("found PLAINTEXT");
        });
    }

    @Test
    void theProducerOverrideWinsOverTheCommonProtocol() {
        withKafkaClient().withPropertyValues(VERIFY_FULL,
                "spring.kafka.security.protocol=SASL_SSL", "spring.kafka.producer.security.protocol=SSL").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .hasMessageContaining("spring.kafka.producer.security.protocol")
                    .hasMessageContaining("found SSL");
        });
    }

    @Test
    void aConsumerIsCheckedToo() {
        runner.withBean(ConsumerFactory.class, () -> Mockito.mock(ConsumerFactory.class))
                .withPropertyValues(VERIFY_FULL, "spring.kafka.properties.security.protocol=SASL_PLAINTEXT").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .hasMessageContaining("spring.kafka.properties.security.protocol")
                    .hasMessageContaining("found SASL_PLAINTEXT");
        });
    }

    @Test
    void startsWithVerifyFullAndSaslSsl() {
        withKafkaClient().withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theKafkaProtocolIsAssertedOnlyWhenAKafkaClientIsConfigured() {
        // The migration Job context (DataSource and Flyway only) has no Kafka client.
        runner.withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=PLAINTEXT")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void aContextWithoutADatasourceIsNotAsserted() {
        runner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void onlyAnExplicitFalseSwitchesEnforcementOff() {
        withKafkaClient().withPropertyValues(REQUIRE, "spring.kafka.security.protocol=PLAINTEXT",
                "fintechbankx.tls.enforce=false").run(context -> assertThat(context).hasNotFailed());
        withKafkaClient().withPropertyValues(REQUIRE, "fintechbankx.tls.enforce=")
                .run(context -> assertThat(context).hasFailed());
    }

    /** The assertion's own exception, whether or not the context wrapped it. */
    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    @Test
    void isRegisteredForEverySpringApplicationOfThisService() {
        assertThat(SpringFactoriesLoader.forDefaultResourceLocation().load(ApplicationContextInitializer.class))
                .anyMatch(TlsEnforcementInitializer.class::isInstance);
    }
}
