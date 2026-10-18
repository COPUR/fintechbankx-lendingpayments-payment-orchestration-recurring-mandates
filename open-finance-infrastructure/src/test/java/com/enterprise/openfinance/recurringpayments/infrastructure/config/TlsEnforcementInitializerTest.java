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

    // Round 6, guardrail 4a: the URL is read the way PgJDBC reads it (keys case-sensitive and
    // lower case, the last of two sslmode values would win), and every URL a pool can use is read.

    @Test
    void refusesASecondSslmodeEvenWhenTheFirstIsVerifyFull() {
        runner.withPropertyValues(VERIFY_FULL + "&sslmode=disable").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.datasource.url")
                    .hasMessageContaining("sslmode exactly once")
                    .hasMessageContaining("found 2")
                    .hasMessageNotContaining("db.internal");
        });
    }

    @Test
    void anUpperCaseSslmodeIsNoSslmodeBecauseTheDriverIgnoresIt() {
        runner.withPropertyValues(VERIFY_FULL.replace("sslmode=verify-full", "SSLMODE=verify-full")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .hasMessageContaining("found no sslmode")
                    .hasMessageContaining("SSLMODE");
        });
        runner.withPropertyValues(VERIFY_FULL.replace("sslmode=verify-full", "SslMode=verify-full")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).hasMessageContaining("found no sslmode");
        });
    }

    @Test
    void aValueThatOnlyContainsVerifyFullIsNotAnSslmode() {
        runner.withPropertyValues("spring.datasource.url=jdbc:postgresql://db.internal:5432/db_pay"
                + "?sslmode=require&ApplicationName=sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure())).hasMessageContaining("found sslmode=require");
        });
    }

    @Test
    void refusesTheParametersThatBypassCertificateOrHostNameVerification() {
        for (String parameter : new String[] {"sslfactory=org.postgresql.ssl.NonValidatingFactory", "sslfactoryarg=x",
                "sslhostnameverifier=x.Y", "sslpasswordcallback=x", "service=prod"}) {
            String name = parameter.substring(0, parameter.indexOf('='));
            runner.withPropertyValues(VERIFY_FULL + "&" + parameter).run(context -> {
                assertThat(context).as(parameter).hasFailed();
                assertThat(rootCause(context.getStartupFailure())).as(parameter)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("spring.datasource.url")
                        .hasMessageContaining("must not set " + name);
            });
        }
    }

    @Test
    void theHikariJdbcUrlIsReadTooInEitherSpelling() {
        // spring.datasource.hikari.jdbc-url replaces spring.datasource.url for the pool.
        for (String key : new String[] {"spring.datasource.hikari.jdbc-url", "spring.datasource.hikari.jdbcUrl"}) {
            runner.withPropertyValues(VERIFY_FULL, key + "=jdbc:postgresql://db.internal:5432/db_pay?sslmode=require").run(context -> {
                assertThat(context).as(key).hasFailed();
                assertThat(rootCause(context.getStartupFailure())).as(key)
                        .hasMessageContaining("spring.datasource.hikari.jdbc-url")
                        .hasMessageContaining("found sslmode=require")
                        .hasMessageNotContaining("db.internal");
            });
        }
        runner.withPropertyValues(VERIFY_FULL, "spring.datasource.hikari.jdbc-url=" + VERIFY_FULL.substring(VERIFY_FULL.indexOf('=') + 1))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void theFlywayUrlIsReadToo() {
        runner.withPropertyValues(VERIFY_FULL, "spring.flyway.url=jdbc:postgresql://db.internal:5432/db_pay?sslmode=require").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .hasMessageContaining("spring.flyway.url")
                    .hasMessageContaining("found sslmode=require");
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
        // Strimzi mutual TLS on the producer override passes even when the common setting is plain.
        withKafkaClient().withPropertyValues(VERIFY_FULL,
                "spring.kafka.security.protocol=PLAINTEXT", "spring.kafka.producer.security.protocol=SSL")
                .run(context -> assertThat(context).hasNotFailed());
        // And a plain producer override is refused even when the common setting is SASL_SSL.
        withKafkaClient().withPropertyValues(VERIFY_FULL,
                "spring.kafka.security.protocol=SASL_SSL", "spring.kafka.producer.security.protocol=PLAINTEXT").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .hasMessageContaining("spring.kafka.producer.security.protocol")
                    .hasMessageContaining("found PLAINTEXT");
        });
    }

    @Test
    void theRawPropertiesOverridesArePlainTextTooWhenTheySaySo() {
        // Read the way KafkaProperties builds the client configuration, not from one key.
        withKafkaClient().withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL",
                "spring.kafka.producer.properties.security.protocol=PLAINTEXT").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .hasMessageContaining("Kafka producer")
                    .hasMessageContaining("spring.kafka.producer.properties.security.protocol")
                    .hasMessageContaining("found PLAINTEXT");
        });
        withKafkaClient().withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL",
                "spring.kafka.properties.security.protocol=PLAINTEXT").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .hasMessageContaining("spring.kafka.properties.security.protocol")
                    .hasMessageContaining("found PLAINTEXT");
        });
        // The consumer's own override is read for the consumer, and only there.
        runner.withBean(ConsumerFactory.class, () -> Mockito.mock(ConsumerFactory.class))
                .withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL",
                        "spring.kafka.consumer.properties.security.protocol=PLAINTEXT").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .hasMessageContaining("Kafka consumer")
                    .hasMessageContaining("spring.kafka.consumer.properties.security.protocol");
        });
        withKafkaClient().withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_SSL",
                "spring.kafka.consumer.properties.security.protocol=PLAINTEXT").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void acceptsStrimziMutualTlsAsWellAsSaslSsl() {
        withKafkaClient().withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SSL")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesSaslWithoutTls() {
        withKafkaClient().withPropertyValues(VERIFY_FULL, "spring.kafka.security.protocol=SASL_PLAINTEXT").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCause(context.getStartupFailure()))
                    .hasMessageContaining("SASL_SSL or SSL")
                    .hasMessageContaining("found SASL_PLAINTEXT");
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
