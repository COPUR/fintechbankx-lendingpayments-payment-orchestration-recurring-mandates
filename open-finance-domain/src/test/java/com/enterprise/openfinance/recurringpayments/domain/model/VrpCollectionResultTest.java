package com.enterprise.openfinance.recurringpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VrpCollectionResultTest {

    @Test
    void shouldExposeCollectionResult() {
        VrpCollectionResult result = new VrpCollectionResult(
                "PAY-VRP-001",
                "CONS-VRP-001",
                VrpPaymentStatus.ACCEPTED,
                "ix-1",
                Instant.parse("2026-02-09T10:00:00Z"),
                false
        );

        assertThat(result.paymentId()).isEqualTo("PAY-VRP-001");
        assertThat(result.idempotencyReplay()).isFalse();
    }

    @Test
    void shouldNormalizeFields() {
        VrpCollectionResult result = new VrpCollectionResult(
                " PAY-VRP-001 ",
                " CONS-VRP-001 ",
                VrpPaymentStatus.ACCEPTED,
                " ix-1 ",
                Instant.parse("2026-02-09T10:00:00Z"),
                true
        );

        assertThat(result.paymentId()).isEqualTo("PAY-VRP-001");
        assertThat(result.consentId()).isEqualTo("CONS-VRP-001");
        assertThat(result.interactionId()).isEqualTo("ix-1");
        assertThat(result.idempotencyReplay()).isTrue();
    }

    @Test
    void shouldRejectBlankResultFields() {
        assertInvalid("", "CONS-VRP-001", VrpPaymentStatus.ACCEPTED, "ix-1", Instant.parse("2026-02-09T10:00:00Z"), "paymentId");
        assertInvalid("PAY-VRP-001", "", VrpPaymentStatus.ACCEPTED, "ix-1", Instant.parse("2026-02-09T10:00:00Z"), "consentId");
        assertInvalid("PAY-VRP-001", "CONS-VRP-001", null, "ix-1", Instant.parse("2026-02-09T10:00:00Z"), "status");
        assertInvalid("PAY-VRP-001", "CONS-VRP-001", VrpPaymentStatus.ACCEPTED, "", Instant.parse("2026-02-09T10:00:00Z"), "interactionId");
        assertInvalid("PAY-VRP-001", "CONS-VRP-001", VrpPaymentStatus.ACCEPTED, "ix-1", null, "createdAt");
    }

    private static void assertInvalid(String paymentId,
                                      String consentId,
                                      VrpPaymentStatus status,
                                      String interactionId,
                                      Instant createdAt,
                                      String expectedField) {
        assertThatThrownBy(() -> new VrpCollectionResult(
                paymentId,
                consentId,
                status,
                interactionId,
                createdAt,
                false
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
