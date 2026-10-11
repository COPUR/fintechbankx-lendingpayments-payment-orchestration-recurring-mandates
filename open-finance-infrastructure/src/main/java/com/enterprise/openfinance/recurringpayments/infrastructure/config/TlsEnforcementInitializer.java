package com.enterprise.openfinance.recurringpayments.infrastructure.config;

import org.apache.kafka.clients.CommonClientConfigs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Startup TLS assertion (governance round 3, answer 2b; round 6, guardrail 4a).
 * Every Spring application of this service (the API pods, the migration Job, the
 * test contexts) fails fast unless
 * <ul>
 *   <li>every datasource URL a pool can use ({@code spring.datasource.url},
 *   {@code spring.datasource.hikari.jdbc-url}, {@code spring.flyway.url}), read
 *   the way PgJDBC reads it, carries exactly one lower-case
 *   {@code sslmode=verify-full} and none of the parameters that bypass
 *   certificate or host name verification ({@code sslfactory},
 *   {@code sslfactoryarg}, {@code sslhostnameverifier},
 *   {@code sslpasswordcallback}, {@code service}). The driver's keys are
 *   case-sensitive, so {@code SSLMODE=verify-full} is no sslmode at all, and a
 *   second {@code sslmode} would win over the first; both are refused.</li>
 *   <li>when a Kafka client is configured (a producer or consumer factory bean
 *   exists, which the migration Job never has), the effective
 *   {@code security.protocol} of the client configuration Spring Boot builds
 *   ({@link KafkaProperties#buildProducerProperties}, and
 *   {@link KafkaProperties#buildConsumerProperties} when a consumer exists)
 *   encrypts in transit: {@code SASL_SSL} (MSK with IAM) or {@code SSL}
 *   (Strimzi mutual TLS with the KafkaUser certificate). {@code PLAINTEXT},
 *   {@code SASL_PLAINTEXT} and unset (Kafka's default is plain) are refused,
 *   whichever of the common, raw-properties or per-client settings says so.</li>
 * </ul>
 * {@code fintechbankx.tls.enforce} is true unless it is explicitly
 * {@code false}, which only local and test configuration may set (the
 * {@code local} profile and the bootstrap test resources). The chart refuses the
 * key, the profile and every JVM option or Spring key that could reach them. The
 * failure message names the offending setting and the value found, never the URL
 * (it can carry credentials).
 *
 * Registered in {@code META-INF/spring.factories}, so the migration Job's
 * context is covered too; the check runs as a bean factory post-processor
 * because the Kafka beans are known only once the auto-configuration has
 * registered its definitions.
 */
public final class TlsEnforcementInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String ENFORCE = "fintechbankx.tls.enforce";
    static final String DATASOURCE_URL = "spring.datasource.url";
    /** Every URL a pool or Flyway can use; each one present is held to the rule. */
    static final List<String> DATASOURCE_URLS = List.of(DATASOURCE_URL, "spring.datasource.hikari.jdbc-url", "spring.flyway.url");
    static final String REQUIRED_SSLMODE = "verify-full";
    /** PgJDBC parameters that replace or weaken certificate and host name verification, or load settings from pg_service.conf. */
    static final List<String> FORBIDDEN_PARAMETERS = List.of("sslfactory", "sslfactoryarg", "sslhostnameverifier", "sslpasswordcallback", "service");
    /** Protocols that encrypt in transit: SASL_SSL (MSK, IAM) and SSL (Strimzi mutual TLS). */
    static final List<String> ACCEPTED_PROTOCOLS = List.of("SASL_SSL", "SSL");
    /** Kafka's default when no security.protocol is configured. */
    static final String KAFKA_DEFAULT_PROTOCOL = "PLAINTEXT";

    private static final Logger log = LoggerFactory.getLogger(TlsEnforcementInitializer.class);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.addBeanFactoryPostProcessor(beanFactory -> assertTls(context.getEnvironment(), beanFactory));
    }

    static void assertTls(Environment environment, ListableBeanFactory beans) {
        if (!enforced(environment)) {
            log.warn("{}=false: the startup TLS assertion is off (local or test configuration only)", ENFORCE);
            return;
        }
        assertDatasources(environment);
        if (hasBean(beans, ProducerFactory.class) || hasBean(beans, KafkaTemplate.class)) {
            assertKafkaProtocol(environment, "producer");
        }
        if (hasBean(beans, ConsumerFactory.class)) {
            assertKafkaProtocol(environment, "consumer");
        }
    }

    /** Only an explicit {@code false} switches the assertion off; anything else (absent, blank, other) enforces. */
    static boolean enforced(Environment environment) {
        String value = environment.getProperty(ENFORCE);
        return value == null || !value.trim().equalsIgnoreCase("false");
    }

    private static void assertDatasources(Environment environment) {
        // Bound, not read by exact key: Hikari binds jdbc-url and jdbcUrl alike, so must this check.
        Binder binder = Binder.get(environment);
        for (String setting : DATASOURCE_URLS) {
            String url = binder.bind(setting, String.class).orElse(null);
            if (url != null && !url.isBlank()) {
                assertJdbcUrl(setting, url.trim());
            }
        }
    }

    /** The rule on one JDBC URL, read the way PgJDBC reads it: query after the first '?', split on '&', key before the first '=', keys case-sensitive. */
    static void assertJdbcUrl(String setting, String url) {
        List<String[]> parameters = queryParameters(url);
        for (String forbidden : FORBIDDEN_PARAMETERS) {
            if (parameters.stream().anyMatch(parameter -> parameter[0].equals(forbidden))) {
                throw failure(setting + " must not set " + forbidden + " (it can bypass certificate or host name verification)");
            }
        }
        List<String> modes = parameters.stream().filter(parameter -> parameter[0].equals("sslmode")).map(parameter -> parameter[1]).toList();
        if (modes.size() > 1) {
            throw failure(setting + " must set sslmode exactly once (found " + modes.size() + "; PgJDBC takes the last one)");
        }
        if (modes.isEmpty()) {
            boolean otherCase = parameters.stream().anyMatch(parameter -> parameter[0].equalsIgnoreCase("sslmode"));
            throw failure(setting + " must carry sslmode=" + REQUIRED_SSLMODE + " (found no sslmode parameter"
                    + (otherCase ? "; SSLMODE in another case is ignored by PgJDBC, whose keys are case-sensitive" : "") + ")");
        }
        if (!REQUIRED_SSLMODE.equals(modes.get(0))) {
            throw failure(setting + " must carry sslmode=" + REQUIRED_SSLMODE + " (found sslmode=" + modes.get(0) + ")");
        }
    }

    private static IllegalStateException failure(String problem) {
        return new IllegalStateException(ENFORCE + ": " + problem + "; only local or test configuration may set " + ENFORCE + "=false");
    }

    /**
     * The effective protocol of a Kafka client role as Spring Boot builds its
     * configuration (KafkaProperties: the role's raw properties win over its typed
     * setting, which wins over the common raw properties, which win over the common
     * typed setting), not one key read on its own.
     */
    private static void assertKafkaProtocol(Environment environment, String role) {
        KafkaProperties kafka = Binder.get(environment).bind("spring.kafka", KafkaProperties.class).orElseGet(KafkaProperties::new);
        Map<String, Object> client = role.equals("consumer") ? kafka.buildConsumerProperties(null) : kafka.buildProducerProperties(null);
        Object value = client.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG);
        String protocol = value == null || value.toString().isBlank() ? KAFKA_DEFAULT_PROTOCOL : value.toString().trim();
        if (!ACCEPTED_PROTOCOLS.contains(protocol.toUpperCase(Locale.ROOT))) {
            throw failure("the Kafka " + role + " is configured and " + protocolSetting(environment, role)
                    + " must be " + String.join(" or ", ACCEPTED_PROTOCOLS) + " (found " + protocol + ")");
        }
    }

    /** The setting that decides the role's protocol, for the message: the highest-precedence one that is set. */
    private static String protocolSetting(Environment environment, String role) {
        List<String> precedence = List.of(
                "spring.kafka." + role + ".properties.security.protocol",
                "spring.kafka." + role + ".security.protocol",
                "spring.kafka.properties.security.protocol",
                "spring.kafka.security.protocol");
        for (String key : precedence) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                return key;
            }
        }
        return "spring.kafka.security.protocol";
    }

    private static boolean hasBean(ListableBeanFactory beans, Class<?> type) {
        // Definitions only: nothing is instantiated this early.
        return beans.getBeanNamesForType(type, true, false).length > 0;
    }

    /** The query parameters of a JDBC URL as {key, value} pairs, in order, read the way PgJDBC splits them; empty when there is no '?'. */
    static List<String[]> queryParameters(String url) {
        List<String[]> parameters = new ArrayList<>();
        int query = url.indexOf('?');
        if (query < 0) {
            return parameters;
        }
        for (String pair : url.substring(query + 1).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            parameters.add(new String[] {eq < 0 ? pair : pair.substring(0, eq), eq < 0 ? "" : pair.substring(eq + 1)});
        }
        return parameters;
    }
}
