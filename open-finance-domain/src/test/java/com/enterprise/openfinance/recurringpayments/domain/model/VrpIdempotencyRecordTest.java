package com.enterprise.openfinance.recurringpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VrpIdempotencyRecordTest {

    @Test
    void shouldDetectExpiry() {
        VrpIdempotencyRecord record = new VrpIdempotencyRecord(
                "IDEMP-001",
                "TPP-001",
                "hash-a",
                "PAY-001",
                VrpPaymentStatus.ACCEPTED,
                Instant.parse("2026-02-10T00:00:00Z")
        );

        assertThat(record.isActive(Instant.parse("2026-02-09T00:00:00Z"))).isTrue();
        assertThat(record.isActive(Instant.parse("2026-02-10T00:00:00Z"))).isFalse();
        assertThat(record.isActive(Instant.parse("2026-02-11T00:00:00Z"))).isFalse();
    }

    @Test
    void shouldNormalizeFields() {
        VrpIdempotencyRecord record = new VrpIdempotencyRecord(
                " IDEMP-001 ",
                " TPP-001 ",
                " hash-a ",
                " PAY-001 ",
                VrpPaymentStatus.ACCEPTED,
                Instant.parse("2026-02-10T00:00:00Z")
        );

        assertThat(record.idempotencyKey()).isEqualTo("IDEMP-001");
        assertThat(record.tppId()).isEqualTo("TPP-001");
        assertThat(record.requestHash()).isEqualTo("hash-a");
        assertThat(record.paymentId()).isEqualTo("PAY-001");
    }

    @Test
    void shouldRejectInvalidFields() {
        assertInvalid("", "TPP-001", "hash-a", "PAY-001", VrpPaymentStatus.ACCEPTED, Instant.parse("2026-02-10T00:00:00Z"), "idempotencyKey");
        assertInvalid("IDEMP-001", "", "hash-a", "PAY-001", VrpPaymentStatus.ACCEPTED, Instant.parse("2026-02-10T00:00:00Z"), "tppId");
        assertInvalid("IDEMP-001", "TPP-001", "", "PAY-001", VrpPaymentStatus.ACCEPTED, Instant.parse("2026-02-10T00:00:00Z"), "requestHash");
        assertInvalid("IDEMP-001", "TPP-001", "hash-a", "", VrpPaymentStatus.ACCEPTED, Instant.parse("2026-02-10T00:00:00Z"), "paymentId");
        assertInvalid("IDEMP-001", "TPP-001", "hash-a", "PAY-001", null, Instant.parse("2026-02-10T00:00:00Z"), "status");
        assertInvalid("IDEMP-001", "TPP-001", "hash-a", "PAY-001", VrpPaymentStatus.ACCEPTED, null, "expiresAt");
    }

    private static void assertInvalid(String idempotencyKey,
                                      String tppId,
                                      String requestHash,
                                      String paymentId,
                                      VrpPaymentStatus status,
                                      Instant expiresAt,
                                      String expectedField) {
        assertThatThrownBy(() -> new VrpIdempotencyRecord(
                idempotencyKey,
                tppId,
                requestHash,
                paymentId,
                status,
                expiresAt
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
