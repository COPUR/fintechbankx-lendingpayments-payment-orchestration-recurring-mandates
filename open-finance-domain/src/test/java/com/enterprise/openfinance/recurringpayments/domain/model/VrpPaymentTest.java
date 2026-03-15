package com.enterprise.openfinance.recurringpayments.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VrpPaymentTest {

    @Test
    void shouldCreateAcceptedPaymentAndExposeSuccessFlag() {
        VrpPayment payment = new VrpPayment(
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

        assertThat(payment.isAccepted()).isTrue();
        assertThat(payment.periodKey()).isEqualTo("2026-02");
    }

    @Test
    void shouldNormalizeAndHandleNonAcceptedStatus() {
        VrpPayment payment = new VrpPayment(
                " PAY-VRP-001 ",
                " CONS-VRP-001 ",
                " TPP-001 ",
                " IDEMP-001 ",
                new BigDecimal("100.00"),
                " AED ",
                " 2026-02 ",
                VrpPaymentStatus.REJECTED,
                Instant.parse("2026-02-09T10:00:00Z")
        );

        assertThat(payment.paymentId()).isEqualTo("PAY-VRP-001");
        assertThat(payment.consentId()).isEqualTo("CONS-VRP-001");
        assertThat(payment.tppId()).isEqualTo("TPP-001");
        assertThat(payment.idempotencyKey()).isEqualTo("IDEMP-001");
        assertThat(payment.currency()).isEqualTo("AED");
        assertThat(payment.periodKey()).isEqualTo("2026-02");
        assertThat(payment.isAccepted()).isFalse();
    }

    @Test
    void shouldRejectInvalidPaymentData() {
        assertInvalid("", "CONS-VRP-001", "TPP-001", "IDEMP-001", new BigDecimal("100.00"), "AED", "2026-02", VrpPaymentStatus.ACCEPTED, Instant.parse("2026-02-09T10:00:00Z"), "paymentId");
        assertInvalid("PAY", "", "TPP-001", "IDEMP-001", new BigDecimal("100.00"), "AED", "2026-02", VrpPaymentStatus.ACCEPTED, Instant.parse("2026-02-09T10:00:00Z"), "consentId");
        assertInvalid("PAY", "CONS", "", "IDEMP-001", new BigDecimal("100.00"), "AED", "2026-02", VrpPaymentStatus.ACCEPTED, Instant.parse("2026-02-09T10:00:00Z"), "tppId");
        assertInvalid("PAY", "CONS", "TPP", "", new BigDecimal("100.00"), "AED", "2026-02", VrpPaymentStatus.ACCEPTED, Instant.parse("2026-02-09T10:00:00Z"), "idempotencyKey");
        assertInvalid("PAY", "CONS", "TPP", "IDEMP", null, "AED", "2026-02", VrpPaymentStatus.ACCEPTED, Instant.parse("2026-02-09T10:00:00Z"), "amount");
        assertInvalid("PAY", "CONS", "TPP", "IDEMP", new BigDecimal("0.00"), "AED", "2026-02", VrpPaymentStatus.ACCEPTED, Instant.parse("2026-02-09T10:00:00Z"), "amount");
        assertInvalid("PAY", "CONS", "TPP", "IDEMP", new BigDecimal("100.00"), "", "2026-02", VrpPaymentStatus.ACCEPTED, Instant.parse("2026-02-09T10:00:00Z"), "currency");
        assertInvalid("PAY", "CONS", "TPP", "IDEMP", new BigDecimal("100.00"), "AED", "", VrpPaymentStatus.ACCEPTED, Instant.parse("2026-02-09T10:00:00Z"), "periodKey");
        assertInvalid("PAY", "CONS", "TPP", "IDEMP", new BigDecimal("100.00"), "AED", "2026-02", null, Instant.parse("2026-02-09T10:00:00Z"), "status");
        assertInvalid("PAY", "CONS", "TPP", "IDEMP", new BigDecimal("100.00"), "AED", "2026-02", VrpPaymentStatus.ACCEPTED, null, "createdAt");
    }

    private static void assertInvalid(String paymentId,
                                      String consentId,
                                      String tppId,
                                      String idempotencyKey,
                                      BigDecimal amount,
                                      String currency,
                                      String periodKey,
                                      VrpPaymentStatus status,
                                      Instant createdAt,
                                      String expectedField) {
        assertThatThrownBy(() -> new VrpPayment(
                paymentId,
                consentId,
                tppId,
                idempotencyKey,
                amount,
                currency,
                periodKey,
                status,
                createdAt
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedField);
    }
}
