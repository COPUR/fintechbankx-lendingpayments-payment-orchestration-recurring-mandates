package com.enterprise.openfinance.recurringpayments.infrastructure.persistence;

import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsent;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpConsentStatus;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpIdempotencyRecord;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPayment;
import com.enterprise.openfinance.recurringpayments.domain.model.VrpPaymentStatus;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity.VrpIdempotencyJpaEntity;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity.VrpMandateJpaEntity;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.entity.VrpPaymentJpaEntity;
import com.enterprise.openfinance.recurringpayments.infrastructure.persistence.mapper.VrpPersistenceMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class VrpPersistenceMapperTest {

    private static final Instant NOW = Instant.parse("2026-02-09T10:00:00Z");

    @Test
    void mandateRoundTripsIncludingVersionDebtorAccountAndRevocation() {
        VrpConsent mandate = new VrpConsent("CONS-1", "TPP-001", "PSU-001", new BigDecimal("5000.00"), "AED",
                VrpConsentStatus.REVOKED, Instant.parse("2099-01-01T00:00:00Z"), NOW, "ACC-1", 4L);

        VrpMandateJpaEntity entity = VrpPersistenceMapper.toEntity(mandate, NOW);

        assertThat(entity.getStatus()).isEqualTo("REVOKED");
        assertThat(entity.getCreatedAt()).isEqualTo(NOW);
        assertThat(entity.getUpdatedAt()).isEqualTo(NOW);
        assertThat(VrpPersistenceMapper.toDomain(entity)).isEqualTo(mandate);
    }

    @Test
    void paymentAndIdempotencyRecordRoundTrip() {
        VrpPayment payment = new VrpPayment("PAY-1", "CONS-1", "TPP-001", "IDEMP-1", new BigDecimal("12.50"), "AED",
                "2026-02", VrpPaymentStatus.ACCEPTED, NOW);

        VrpPaymentJpaEntity entity = VrpPersistenceMapper.toEntity(payment);
        assertThat(entity.getStatus()).isEqualTo("ACCEPTED");
        assertThat(VrpPersistenceMapper.toDomain(entity)).isEqualTo(payment);

        VrpIdempotencyJpaEntity record = new VrpIdempotencyJpaEntity(new VrpIdempotencyJpaEntity.Key("TPP-001", "IDEMP-1"),
                "CONS-1|12.50|AED", "PAY-1", "ACCEPTED", NOW.plusSeconds(60), NOW);
        assertThat(VrpPersistenceMapper.toDomain(record)).isEqualTo(new VrpIdempotencyRecord(
                "IDEMP-1", "TPP-001", "CONS-1|12.50|AED", "PAY-1", VrpPaymentStatus.ACCEPTED, NOW.plusSeconds(60)));
        assertThat(record.getCreatedAt()).isEqualTo(NOW);
        assertThat(new VrpIdempotencyJpaEntity.Key("TPP-001", "IDEMP-1"))
                .isEqualTo(record.getKey())
                .hasSameHashCodeAs(record.getKey())
                .isNotEqualTo(new VrpIdempotencyJpaEntity.Key("TPP-002", "IDEMP-1"));
    }
}
