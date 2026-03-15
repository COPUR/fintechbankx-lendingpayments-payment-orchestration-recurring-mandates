package com.enterprise.openfinance.recurringpayments.infrastructure.cache;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsentStatus;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPaymentStatus;
import com.enterprise.openfinance.recurringpayments.infrastructure.config.RecurringPaymentsCacheProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("unit")
class InMemoryVrpCacheAdapterTest {

    @Test
    void shouldCacheAndExpireConsentAndPayment() {
        InMemoryVrpCacheAdapter adapter = new InMemoryVrpCacheAdapter(properties(10));
        Instant now = Instant.parse("2026-02-09T10:00:00Z");

        adapter.putConsent("consent", consent(), now.plusSeconds(5));
        adapter.putPayment("payment", payment(), now.plusSeconds(5));

        assertThat(adapter.getConsent("consent", now.plusSeconds(1))).isPresent();
        assertThat(adapter.getPayment("payment", now.plusSeconds(1))).isPresent();

        assertThat(adapter.getConsent("consent", now.plusSeconds(10))).isEmpty();
        assertThat(adapter.getPayment("payment", now.plusSeconds(10))).isEmpty();
    }

    @Test
    void shouldEvictWhenCapacityExceeded() {
        InMemoryVrpCacheAdapter adapter = new InMemoryVrpCacheAdapter(properties(1));
        Instant now = Instant.parse("2026-02-09T10:00:00Z");

        adapter.putConsent("k1", consent(), now.plusSeconds(10));
        adapter.putConsent("k2", consent(), now.plusSeconds(10));

        assertThat(adapter.getConsent("k2", now.plusSeconds(1))).isPresent();
        assertThat(adapter.getConsent("k1", now.plusSeconds(1))).isEmpty();
    }

    private static RecurringPaymentsCacheProperties properties(int maxEntries) {
        RecurringPaymentsCacheProperties properties = new RecurringPaymentsCacheProperties();
        properties.setTtl(Duration.ofSeconds(30));
        properties.setMaxEntries(maxEntries);
        return properties;
    }

    private static VrpConsent consent() {
        return new VrpConsent(
                "CONS-VRP-001",
                "TPP-001",
                "PSU-001",
                new BigDecimal("5000.00"),
                "AED",
                VrpConsentStatus.AUTHORISED,
                Instant.parse("2099-01-01T00:00:00Z"),
                null
        );
    }

    private static VrpPayment payment() {
        return new VrpPayment(
                "PAY-VRP-001",
                "CONS-VRP-001",
                "TPP-001",
                "IDEMP-001",
                new BigDecimal("100.00"),
                "AED",
                "2026-02",
                VrpPaymentStatus.ACCEPTED,
                Instant.parse("2026-02-09T10:00:00Z")
        );
    }
}
