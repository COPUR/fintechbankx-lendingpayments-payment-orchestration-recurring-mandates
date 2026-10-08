package com.enterprise.openfinance.recurringpayments;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka refuses to build a producer unless delivery.timeout.ms >= linger.ms +
 * request.timeout.ms, and the relay must wait longer than Kafka keeps
 * retrying a send. Runs without a broker or a database.
 */
class KafkaProducerConfigurationTest {

    @Test
    void producerTimeoutsLetKafkaConstructTheProducerAndTheRelayOutwaitsDelivery() throws Exception {
        PropertySource<?> base = load("application.yml");
        long delivery = number(base, "spring.kafka.producer.properties.delivery.timeout.ms");
        long request = number(base, "spring.kafka.producer.properties.request.timeout.ms");
        long linger = number(base, "spring.kafka.producer.properties.linger.ms");

        assertThat(delivery).isGreaterThanOrEqualTo(linger + request);
        assertThat(Duration.parse(base.getProperty("mandates.outbox.relay.send-timeout").toString()).toMillis())
                .isGreaterThan(delivery);

        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", "localhost:9092");
        config.put("client.id", base.getProperty("spring.kafka.client-id"));
        config.put("key.serializer", StringSerializer.class);
        config.put("value.serializer", StringSerializer.class);
        config.put("acks", "all");
        config.put("enable.idempotence", true);
        config.put("compression.type", base.getProperty("spring.kafka.producer.compression-type"));
        config.put("delivery.timeout.ms", (int) delivery);
        config.put("request.timeout.ms", (int) request);
        config.put("linger.ms", (int) linger);
        // Construction validates the timeout rule; no broker is contacted.
        new KafkaProducer<String, String>(config).close(Duration.ZERO);
    }

    @Test
    void kafkaProfilesDoNotOverrideTheProducerTimeouts() throws Exception {
        for (String profile : new String[] {"application-kafka-msk.yml", "application-kafka-strimzi.yml"}) {
            PropertySource<?> overlay = load(profile);
            assertThat(overlay.getProperty("spring.kafka.producer.properties.delivery.timeout.ms")).as(profile).isNull();
            assertThat(overlay.getProperty("spring.kafka.producer.properties.request.timeout.ms")).as(profile).isNull();
            assertThat(overlay.getProperty("spring.kafka.producer.properties.linger.ms")).as(profile).isNull();
        }
    }

    private static long number(PropertySource<?> source, String key) {
        Object value = source.getProperty(key);
        assertThat(value).as(key).isNotNull();
        return Long.parseLong(value.toString());
    }

    private static PropertySource<?> load(String file) throws Exception {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).get(0);
    }
}
