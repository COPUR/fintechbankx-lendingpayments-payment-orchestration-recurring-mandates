package com.enterprise.openfinance.recurringpayments.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.List;

/**
 * Startup TLS assertion (governance round 3, answer 2b). Every Spring
 * application of this service (the API pods, the migration Job, the test
 * contexts) fails fast unless
 * <ul>
 *   <li>the datasource URL carries {@code sslmode=verify-full} (the pods verify
 *   Aurora's certificate against the mounted RDS CA bundle), and</li>
 *   <li>when a Kafka client is configured (a producer or consumer factory bean
 *   exists, which the migration Job never has), its effective
 *   {@code security.protocol} is {@code SASL_SSL}.</li>
 * </ul>
 * {@code fintechbankx.tls.enforce} is true unless it is explicitly
 * {@code false}, which only local and test configuration may set (the
 * {@code local} profile and the bootstrap test resources). The chart refuses the
 * key and the profile. The failure message names the offending setting and the
 * value found, never the URL (it can carry credentials).
 *
 * Registered in {@code META-INF/spring.factories}; the check runs as a bean
 * factory post-processor because the Kafka beans are known only once the
 * auto-configuration has registered its definitions.
 */
public final class TlsEnforcementInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String ENFORCE = "fintechbankx.tls.enforce";
    static final String DATASOURCE_URL = "spring.datasource.url";
    static final String REQUIRED_SSLMODE = "verify-full";
    static final String REQUIRED_PROTOCOL = "SASL_SSL";
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
        assertDatasource(environment);
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

    private static void assertDatasource(Environment environment) {
        String url = environment.getProperty(DATASOURCE_URL);
        if (url == null || url.isBlank()) {
            return; // no datasource in this context: nothing to assert
        }
        String sslmode = queryParameter(url, "sslmode");
        if (!REQUIRED_SSLMODE.equals(sslmode)) {
            throw new IllegalStateException(ENFORCE + ": " + DATASOURCE_URL + " must carry sslmode=" + REQUIRED_SSLMODE
                    + " (" + (sslmode == null ? "found no sslmode parameter" : "found sslmode=" + sslmode) + ")"
                    + "; only local or test configuration may set " + ENFORCE + "=false");
        }
    }

    /**
     * The effective protocol of a Kafka client role as Spring Boot's KafkaProperties
     * builds it: the role's raw properties win over its typed setting, which wins
     * over the common raw properties, which win over the common typed setting.
     */
    private static void assertKafkaProtocol(Environment environment, String role) {
        List<String> precedence = List.of(
                "spring.kafka." + role + ".properties.security.protocol",
                "spring.kafka." + role + ".security.protocol",
                "spring.kafka.properties.security.protocol",
                "spring.kafka.security.protocol");
        String source = "spring.kafka.security.protocol";
        String protocol = KAFKA_DEFAULT_PROTOCOL;
        for (String key : precedence) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                source = key;
                protocol = value.trim();
                break;
            }
        }
        if (!REQUIRED_PROTOCOL.equals(protocol)) {
            throw new IllegalStateException(ENFORCE + ": the Kafka " + role + " is configured and " + source
                    + " must be " + REQUIRED_PROTOCOL + " (found " + protocol + ")"
                    + "; only local or test configuration may set " + ENFORCE + "=false");
        }
    }

    private static boolean hasBean(ListableBeanFactory beans, Class<?> type) {
        // Definitions only: nothing is instantiated this early.
        return beans.getBeanNamesForType(type, true, false).length > 0;
    }

    /** The value of a query parameter of a JDBC URL, or null when absent. */
    static String queryParameter(String url, String name) {
        int query = url.indexOf('?');
        if (query < 0) {
            return null;
        }
        for (String pair : url.substring(query + 1).split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            if (key.equalsIgnoreCase(name)) {
                return eq < 0 ? "" : pair.substring(eq + 1);
            }
        }
        return null;
    }
}
